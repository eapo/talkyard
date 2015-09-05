/**
 * Copyright (C) 2013 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package controllers

import actions.ApiActions.{GetAction, StaffGetAction, StaffPostJsonAction}
import debiki.dao.PageStuff
import collection.mutable
import com.debiki.core._
import com.debiki.core.Prelude._
import debiki._
import debiki.ReactJson.{DateEpochOrNull, JsNumberOrNull, JsLongOrNull, JsStringOrNull}
import java.{util => ju}
import play.api.mvc
import play.api.libs.json._
import play.api.mvc.{Action => _, _}
import requests.DebikiRequest
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import Utils.OkSafeJson
import Utils.ValidationImplicits._
import DebikiHttp.{throwBadReq, throwBadRequest, throwNotFound}


/** Handles requests related to forums and forum categories.
 */
object ForumController extends mvc.Controller {

  /** Keep synced with client/forum/list-topics/ListTopicsController.NumNewTopicsPerRequest. */
  val NumTopicsToList = 40


  def loadCategory(id: String) = StaffGetAction { request =>
    val categoryId = Try(id.toInt) getOrElse throwBadRequest("DwE6PU1", "Invalid category id")
    val category = request.dao.loadTheCategory(categoryId)
    val json = categoryToJson(category, recentTopics = Nil, pageStuffById = Map.empty)
    OkSafeJson(json)
  }


  def saveCategory = StaffPostJsonAction(maxLength = 1000) { request =>
    val body = request.body
    val sectionPageId = (body \ "sectionPageId").as[PageId]
    val newTopicTypeInts = (body \ "newTopicTypes").as[List[Int]]
    val newTopicTypes = newTopicTypeInts map { typeInt =>
      PageRole.fromInt(typeInt) getOrElse throwBadReq(
        "DwE7KUP3", s"Bad new topic type int: $typeInt")
    }

    val creteEditCategoryData = CreateEditCategoryData(
      anyId = (body \ "categoryId").asOpt[CategoryId],
      sectionPageId = sectionPageId,
      parentId = (body \ "parentCategoryId").as[CategoryId],
      name = (body \ "name").as[String],
      slug = (body \ "slug").as[String],
      position = (body \ "position").as[Int],
      newTopicTypes = newTopicTypes)

    var resultJson: JsObject = null

    val category = creteEditCategoryData.anyId match {
      case Some(categoryId) =>
        request.dao.editCategory(creteEditCategoryData, editorId = request.theUserId,
          request.theBrowserIdData)
      case None =>
        val (category, _) = request.dao.createCategory(
          creteEditCategoryData, creatorId = request.theUserId, request.theBrowserIdData)
        category
    }

    OkSafeJson(Json.obj(
      "allCategories" -> ReactJson.categoriesJson(category.sectionPageId, request.dao),
      "newCategoryId" -> category.id,
      "newCategorySlug" -> category.slug))
  }


  def listTopics(categoryId: String) = GetAction { request =>
    val categoryIdInt: CategoryId = Try(categoryId.toInt) getOrElse throwBadReq(
      "DwE4KG08", "Bat category id")
    val pageQuery: PageQuery = parseThePageQuery(request)
    val topics = listTopicsInclPinned(categoryIdInt, pageQuery, request.dao,
      includeDescendantCategories = true)
    val pageStuffById = request.dao.loadPageStuff(topics.map(_.pageId))
    val topicsJson: Seq[JsObject] = topics.map(topicToJson(_, pageStuffById))
    val json = Json.obj("topics" -> topicsJson)
    OkSafeJson(json)
  }


  def listCategories(forumId: PageId) = GetAction { request =>
    val categories = request.dao.listSectionCategories(forumId)
    val json = JsArray(categories.map({ category =>
      categoryToJson(category, recentTopics = Nil, pageStuffById = Map.empty)
    }))
    OkSafeJson(json)
  }


  def listCategoriesAndTopics(forumId: PageId) = GetAction { request =>
    val categories = request.dao.listSectionCategories(forumId)

    val recentTopicsByCategoryId =
      mutable.Map[CategoryId, Seq[PagePathAndMeta]]()

    val pageIds = ArrayBuffer[PageId]()
    val pageQuery = PageQuery(PageOrderOffset.ByBumpTime(None), parsePageFilter(request))

    for (category <- categories) {
      val recentTopics = listTopicsInclPinned(category.id, pageQuery, request.dao,
        includeDescendantCategories = true, limit = 6)
      recentTopicsByCategoryId(category.id) = recentTopics
      pageIds.append(recentTopics.map(_.pageId): _*)
    }

    val pageStuffById: Map[PageId, debiki.dao.PageStuff] =
      request.dao.loadPageStuff(pageIds)

    val json = JsArray(categories.map({ category =>
      categoryToJson(category, recentTopicsByCategoryId(category.id), pageStuffById)
    }))

    OkSafeJson(json)
  }


  def listTopicsInclPinned(categoryId: CategoryId, pageQuery: PageQuery, dao: debiki.dao.SiteDao,
        includeDescendantCategories: Boolean, limit: Int = NumTopicsToList)
        : Seq[PagePathAndMeta] = {
    val topics: Seq[PagePathAndMeta] = dao.listPagesInCategory(
      categoryId, includeDescendantCategories, pageQuery, limit)

    // If sorting by bump time, sort pinned topics first. Otherwise, don't.
    val topicsInclPinned = pageQuery.orderOffset match {
      case orderOffset: PageOrderOffset.ByBumpTime if orderOffset.offset.isEmpty =>
        val pinnedTopics = dao.listPagesInCategory(
          categoryId, includeDescendantCategories,
          PageQuery(PageOrderOffset.ByPinOrderLoadOnlyPinned, pageQuery.pageFilter), limit)
        val notPinned = topics.filterNot(topic => pinnedTopics.exists(_.id == topic.id))
        val topicsSorted = (pinnedTopics ++ notPinned) sortBy { topic =>
          val meta = topic.meta
          val pinnedGlobally = meta.pinWhere.contains(PinPageWhere.Globally)
          val pinnedInThisCategory = meta.isPinned && meta.categoryId.contains(categoryId)
          val isPinned = pinnedGlobally || pinnedInThisCategory
          if (isPinned) topic.meta.pinOrder.get // 1..100
          else Long.MaxValue - topic.meta.bumpedOrPublishedOrCreatedAt.getTime // much larger
        }
        topicsSorted
      case _ => topics
    }

    topicsInclPinned
  }

  def parseThePageQuery(request: DebikiRequest[_]): PageQuery =
    parsePageQuery(request) getOrElse throwBadRequest(
      "DwE2KTES7", "No sort-order-offset specified")

  def parsePageQuery(request: DebikiRequest[_]): Option[PageQuery] = {
    val sortOrderStr = request.queryString.getFirst("sortOrder") getOrElse { return None }
    def anyDateOffset = request.queryString.getLong("epoch") map (new ju.Date(_))
    def anyNumOffset = request.queryString.getInt("num")

    val orderOffset: PageOrderOffset = sortOrderStr match {
      case "ByBumpTime" =>
        PageOrderOffset.ByBumpTime(anyDateOffset)
      case "ByLikesAndBumpTime" =>
        (anyNumOffset, anyDateOffset) match {
          case (Some(num), Some(date)) =>
            PageOrderOffset.ByLikesAndBumpTime(Some(num, date))
          case (None, None) =>
            PageOrderOffset.ByLikesAndBumpTime(None)
          case _ =>
            throwBadReq("DwE4KEW21", "Please specify both 'num' and 'epoch' or none at all")
        }
      case x => throwBadReq("DwE05YE2", s"Bad sort order: `$x'")
    }

    val filter = parsePageFilter(request)
    Some(PageQuery(orderOffset, filter))
  }


  def parsePageFilter(request: DebikiRequest[_]): PageFilter =
    request.queryString.getFirst("filter") match {
      case Some("ShowOpenQuestionsTodos") => PageFilter.ShowOpenQuestionsTodos
      case _ => PageFilter.ShowAll
    }


  private def categoryToJson(category: Category, recentTopics: Seq[PagePathAndMeta],
      pageStuffById: Map[PageId, debiki.dao.PageStuff]): JsObject = {
    require(recentTopics.isEmpty || pageStuffById.nonEmpty, "DwE8QKU2")
    val topicsNoAboutCategoryPage = recentTopics.filter(_.pageRole != PageRole.About)
    val recentTopicsJson = topicsNoAboutCategoryPage.map(topicToJson(_, pageStuffById))
    Json.obj(
      "id" -> category.id,
      "name" -> category.name,
      "slug" -> category.slug,
      "newTopicTypes" -> JsArray(category.newTopicTypes.map(t => JsNumber(t.toInt))),
      "position" -> category.position,
      "description" -> category.description,
      "numTopics" -> category.numTopics,
      "recentTopics" -> recentTopicsJson)
  }


  /** For now only. In the future I'll generate the slug when the category is created?
    */
  def categoryNameToSlug(name: String): String = {
    name.toLowerCase.replaceAll(" ", "-") filterNot { char =>
      "()!?[].," contains char
    }
  }


  def topicToJson(topic: PagePathAndMeta, pageStuffById: Map[PageId, PageStuff]): JsObject = {
    val topicStuff = pageStuffById.get(topic.pageId) getOrDie "DwE1F2I7"
    val createdEpoch = topic.meta.createdAt.getTime
    val bumpedEpoch = DateEpochOrNull(topic.meta.bumpedAt)
    val lastReplyEpoch = DateEpochOrNull(topic.meta.lastReplyAt)
    val title = topicStuff.title

    Json.obj(
      "pageId" -> topic.id,
      "pageRole" -> topic.pageRole.toInt,
      "title" -> title,
      "url" -> topic.path.value,
      "categoryId" -> topic.categoryId.getOrDie(
        "DwE49Fk3", s"Topic `${topic.id}', site `${topic.path.siteId}', belongs to no category"),
      "pinOrder" -> JsNumberOrNull(topic.meta.pinOrder),
      "pinWhere" -> JsNumberOrNull(topic.meta.pinWhere.map(_.toInt)),
      // loadPageStuff() loads excerps for pinned topics (and categories).
      "excerpt" -> JsStringOrNull(topicStuff.bodyExcerpt),
      "numPosts" -> JsNumber(topic.meta.numRepliesVisible + 1),
      "numLikes" -> topic.meta.numLikes,
      "numWrongs" -> topic.meta.numWrongs,
      "numBurys" -> topic.meta.numBurys,
      "numUnwanteds" -> topic.meta.numUnwanteds,
      "numOrigPostLikes" -> topic.meta.numOrigPostLikeVotes,
      "numOrigPostReplies" -> topic.meta.numOrigPostRepliesVisible,
      "createdEpoch" -> createdEpoch,
      "bumpedEpoch" -> bumpedEpoch,
      "lastReplyEpoch" -> lastReplyEpoch,
      "answeredAtMs" -> JsLongOrNull(topic.meta.answeredAt.map(_.getTime)),
      "answerPostUniqueId" -> JsNumberOrNull(topic.meta.answerPostUniqueId),
      "plannedAtMs" -> JsLongOrNull(topic.meta.plannedAt.map(_.getTime)),
      "doneAtMs" -> JsLongOrNull(topic.meta.doneAt.map(_.getTime)),
      "closedAtMs" -> JsLongOrNull(topic.meta.closedAt.map(_.getTime)),
      "lockedAtMs" -> JsLongOrNull(topic.meta.lockedAt.map(_.getTime)),
      "frozenAtMs" -> JsLongOrNull(topic.meta.frozenAt.map(_.getTime)))
  }

}

