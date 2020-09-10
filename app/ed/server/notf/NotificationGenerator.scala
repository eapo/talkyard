/**
 * Copyright (C) 2014-2015 Kaj Magnus Lindberg (born 1979)
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

package ed.server.notf

import com.debiki.core.Prelude._
import com.debiki.core._
import debiki._
import debiki.EdHttp.throwForbiddenIf
import ed.server.notf.NotificationGenerator._
import scala.collection.{immutable, mutable}
import scala.util.matching.Regex



/** Finds out what notifications to send when e.g. a new post is created.
  * Also finds out what not-yet-sent notifications to delete if a post is deleted, or if
  * the post is edited and a @mention removed.
  */
case class NotificationGenerator(
  // This is a bit weird: getting both a tx and a dao. Maybe instead NotificationGenerator
  // should be part of the dao it too? — But needs a tx, so can run in the same tx
  // as SitePatcher and gen notfs for changes it did (in the same tx).
  tx: SiteTransaction,
  dao: debiki.dao.SiteDao,
  nashorn: Nashorn,
  config: debiki.Config) {

  dieIf(Globals.isDevOrTest && tx.siteId != dao.siteId, "TyE603RSKHAN3")

  private var notfsToCreate = mutable.ArrayBuffer[Notification]()
  private var notfsToDelete = mutable.ArrayBuffer[NotificationToDelete]()

  BUG // currently harmless. Should remember sent-to by post id too — in case [REMBSENTTO]
  // needs to generate many notfs to the same user, for different posts.
  private var sentToUserIds = new mutable.HashSet[UserId]()

  private var avoidDuplEmailToUserIds = new mutable.HashSet[UserId]()
  private var nextNotfId: Option[NotificationId] = None
  private var anyAuthor: Option[Participant] = None
  private def author: Participant = anyAuthor getOrDie "TyE5RK2WAG8"
  private def siteId = tx.siteId
  private lazy val site: SiteIdHostnames = dao.theSite()

  def generatedNotifications =
    Notifications(
      toCreate = notfsToCreate.toVector,
      toDelete = notfsToDelete.toVector)


  def generateForNewPost(page: Page, newPost: Post, sourceAndHtml: Option[SourceAndHtml],
        anyNewModTask: Option[ModTask], doingModTasks: Seq[ModTask] = Nil,
        skipMentions: Boolean = false): Notifications = {

    require(page.id == newPost.pageId, "TyE74KEW9")
    require(anyNewModTask.isEmpty || doingModTasks.isEmpty, "TyE056KWH5")

    if (newPost.isTitle)
      return generatedNotifications  // [no_title_notfs]

    val anyNewTextAndHtml: Option[TextAndHtml] = sourceAndHtml.map({
      case t: TextAndHtml => t
      case _ =>
        dieIf(Globals.isDevOrTest, "TyE305KTUDP3", "Got a TitleSourceAndHtml")
        return generatedNotifications
    })

    // A new embedded discussions page shouldn't generate a notification, [new_emb_pg_notf]
    // because those pages are lazy auto created – and uninteresting event.
    // Instead, *the first reply* generates a new page notification.
    // (Embedded pages are also auto-created e.g. if there's a Like vote — maybe
    // there'll never be any reply.)
    if (page.meta.pageType == PageType.EmbeddedComments && newPost.isOrigPost)
      return generatedNotifications

    if (anyNewModTask.isDefined) {
      COULD // Move this to a new fn  generateForReviewTask()  instead? [revw_task_notfs]

      // Generate notifications to staff members, so they can review this post. Don't
      // notify others until later, when the post has been approved and is visible.

      val staffUsers: Seq[User] = tx.loadStaffUsers()
      for (staffUser <- staffUsers) {
        avoidDuplEmailToUserIds += staffUser.id
        notfsToCreate += Notification.NewPost(
          NotificationType.NewPostReviewTask,
          id = bumpAndGetNextNotfId(),
          createdAt = newPost.createdAt,
          uniquePostId = newPost.id,
          byUserId = newPost.createdById,
          toUserId = staffUser.id)  // won't send normal notfs too?
      }
    }

    val approverId = newPost.approvedById getOrElse {
      // This post hasn't yet been approved and isn't visible. Don't notify people
      // until later, when staff has reviewed it and made it visible.
      // We've notified staff already, above, so they can take a look.
      dieIf(anyNewModTask.isEmpty, "TyE0REVTSK")  // [703RK2]
      return generatedNotifications
    }

    // Don't send emails twice to the staff — they've gotten a post-to-review notf already about
    // this post (see above). Do however create notfs — it's nice to have any notification
    // about e.g. a @mention of oneself, in the mentions list, also if one approved
    // that post, oneself.
    val oldNotfsToStaff = tx.loadNotificationsAboutPost(newPost.id, NotificationType.NewPostReviewTask)
    avoidDuplEmailToUserIds ++= oldNotfsToStaff.map(_.toUserId)  ; BUG // doesn't work?
          // sends me a 2nd email anywa

    anyAuthor = Some(tx.loadTheParticipant(newPost.createdById))

    anyNewTextAndHtml foreach { textAndHtml =>
      require(newPost.approvedSource is textAndHtml.text,
        s"approvedSource: ${newPost.approvedSource}, textAndHtml.text: ${textAndHtml.text} [TyE3WASC2]")
      require(newPost.approvedHtmlSanitized is textAndHtml.safeHtml,
        s"appr.HtmlSan.: ${newPost.approvedHtmlSanitized}, safeHtml: ${textAndHtml.safeHtml} [TyE9FJB0]")
    }

    val ancestorsParentFirst = page.parts.ancestorsParentFirstOf(newPost)
    val anyParent = page.parts.parentOf(newPost)
    dieIf(ancestorsParentFirst.headOption != anyParent, "TyE395RSKT")

    // Direct and indirect reply notification.
    val anyParentPost = ancestorsParentFirst.headOption

    def maybeGenReplyNotf(notfType: NotificationType, ancestorsCloseFirst: Seq[Post])
          : Unit = {
      for {
        replyingToPost <- ancestorsCloseFirst
        if replyingToPost.createdById != newPost.createdById // not replying to oneself
        if approverId != replyingToPost.createdById // the approver has already read newPost
        replyingToUser <- tx.loadParticipant(replyingToPost.createdById)
      } {
        val isParent = anyParentPost.is(replyingToPost)
        val __notfType =
              if (isParent) NotificationType.DirectReply
              else NotificationType.IndirectReply
        // (If the replying-to-post is by a group (currently cannot happen), and someone in the group
        // replies to that group, then hen might get a notf about hens own reply. Fine, not much to
        // do about that.)
        makeAboutPostNotfs(
              notfType, newPost, inCategoryId = page.categoryId, replyingToUser)
      }
    }

    // Direct replies have highest precedence.
    // Say a post is a direct reply, and also @mentions the one it replies to
    // — then we'll generate a direct reply notf only.
    maybeGenReplyNotf(NotificationType.DirectReply, anyParentPost.toSeq)

    def notfCreatedAlreadyTo(userId: UserId) =
      generatedNotifications.toCreate.map(_.toUserId).contains(userId)

    val pageMemberIds: Set[UserId] = tx.loadMessageMembers(newPost.pageId)

    // Mentions
    BUG // harmless. If a mention is removed, and added back, a new notf is sent. TyT2ABKS057
    // Probably don't want that?
    if (!skipMentions) {
      val mentionedUsernames = anyNewTextAndHtml.map(_.usernameMentions) getOrElse findMentions(  // [nashorn_in_tx] [save_post_lns_mentions]
            newPost.approvedSource getOrDie "DwE82FK4", site, nashorn)

      var mentionedMembers: Set[Participant] = mentionedUsernames.flatMap(tx.loadMemberByUsername)

      // Can create more mention aliases, like @new-members (= trust levels new & basic only),
      // and @guests and @here-now and @everyone (= all members)
      val allMentioned = mentionsAllInChannel(mentionedUsernames)
      if (allMentioned) {
        if (mayMentionGroups(author)) {
          // ((If user U is a page member, and also mentioned via group G,
          // then, removing G.id here, won't remove U from moreToAdd.
          // Instead, U is added to moreToAdd, and will be @channel mentioned,
          // instead of @group_name mentioned. Doesn't matter?))
          val moreToAdd: Set[UserId] = pageMemberIds -- mentionedMembers.map(_.id)
          mentionedMembers ++= tx.loadUsersAsMap(moreToAdd).values.toSet
        }
      }

      for {
        userOrGroup <- mentionedMembers
        // Right now ignore self-mentions. Later, allow? Could work like a personal to-do item?
        // Then would have to remove a db constraint. Could do later. Right now feels best
        // to keep it so it'll catch bugs.
        // If mentioning a group that one is a member of, one shouldn't and won't be notified (5ABKRW2).
        if userOrGroup.id != newPost.createdById  // poster mentions henself?
        if !notfCreatedAlreadyTo(userOrGroup.id)
        // Authz checks that we won't notify people outside a private chat
        // about any mentions (because they cannot see the chat). [PRIVCHATNOTFS]
      } {
        makeAboutPostNotfs(
            NotificationType.Mention, newPost, inCategoryId = page.categoryId,
            userOrGroup)
      }
    }

    // Indirect replies.
    // If the post @mentions some of those indirectly replied to, then we've won't
    // generate any indirect reply notfs to theme — they'll get a Mention
    // notf instead (generated above).
    maybeGenReplyNotf(NotificationType.IndirectReply, ancestorsParentFirst drop 1)

    // People watching this topic or category
    addWatchingSomethingNotfs(page, newPost, pageMemberIds)

    generatedNotifications
  }


  private def addWatchingSomethingNotfs(page: Page, newPost: Post,
        pageMemberIds: Set[UserId]): Unit = {

    val isEmbDiscFirstReply =
          page.pageType == PageType.EmbeddedComments &&
          newPost.isOrigPostReply && newPost.isSomeVersionApproved && (
            // Currently not decided if `page` should includes newPost or not.
            // So let's try both: 1) If `newPost` not incl in `page`:
            page.meta.numRepliesVisible == 0 ||
            // Or 2) if it *is*:
            (page.meta.numRepliesVisible == 1 &&
                page.parts.lastVisibleReply.exists(p => p.id == newPost.id)))

    val minNotfLevel =
      if (isEmbDiscFirstReply) {
        // This is the first reply in an auto-created embedded discussion — time
        // to create the new page notification.  [new_emb_pg_notf]
        // (We didn't do that when the page got lazy-auto-created — that could have
        // been just someone configuring page notf prefs; then, a page id is needed,
        // but we don't want to get notified about that.)
        NotfLevel.WatchingFirst
      }
      else if (newPost.isOrigPost) {
        // Everyone with a notf level for this page / category / whole-site, at or above
        // WatchingFirst, want to know about this.
        NotfLevel.WatchingFirst
        // Later: NotificationType = new topic
      }
      // Later:
      // else if is Answer, NotificationType.Solved ... or if is Progress,
      // NotificationType.Progress, post ... or status change ...
      // or if closed, NotificationType.TopicClosed
      // etc
      else {
        // Just an ordinary reply. Only people with this high notf level, want to know about it.
        NotfLevel.WatchingAll
      }

    // Page or category subscriptions.
    //
    // Notf prefs for more specific things, content structure wise, have precedence.
    // So, we first look at all notf settings for specific pages, and generate (or skip) notfs,
    // as specified by those page specific notf prefs. And then, we look at categories,
    // and generate notifications, as specified by the for-pages-in-this-category notf prefs.
    // Then (not impl) tags. And lastly, whole site notf prefs settings.
    // (Categories are more specific than tags? Because a page can be in only one category,
    // but it can have many tags. So, tags are more promiscuous, less specific.)
    //
    // Within the same content structure level, a user's own prefs, has precedence, over
    // preferences for groups hen is a member of. If, however,
    // hen hasn't configured any prefs, then the most talkative/chatty pref, of all groups hen
    // is in, wins. [CHATTYPREFS]  Rationale: This is discussion software, so when in doubt,
    // better notify people that a discussion is going on. Talking is ... sort of the whole point?
    // Also, one can just mute the topic or category. Or leave the group with too "noisy" settings.
    // If, however, the most *silent* setting "won", so *no* notfs were sent, then one wouldn't
    // have the chance to realize that there're conflicting notf prefs (because one didn't
    // get any notfs).

    val memberIdsHandled = mutable.HashSet[UserId]()

    // ----- Page

    val notfPrefsOnPage = tx.loadPageNotfPrefsOnPage(page.id)

    // Add default NotfLevel.WatchingAll for private topic members [PRIVCHATNOTFS]
    // — unless they've configured another notf pref.
    // (This wouldn't be needed if [page_pps_t] instead.)
    val privTopicPrefsOnPage =
      if (!page.meta.pageType.isPrivateGroupTalk) Nil
      else pageMemberIds flatMap { id: UserId =>
        if (notfPrefsOnPage.exists(_.peopleId == id)) None  // [On2]
        else Some(PageNotfPref(
          peopleId = id,
          NotfLevel.WatchingAll,
          pageId = Some(page.id)))
      }

    val allPrefsOnPage = notfPrefsOnPage ++ privTopicPrefsOnPage

    makeNewPostSubscrNotfFor(
          allPrefsOnPage, newPost, isEmbDiscFirstReply, minNotfLevel, memberIdsHandled)

    // If private page, skip cat & whole site notf prefs
    // — only page members and people (like moderators) who explicitly follow
    // this page, get notified. — So, forum admins won't get notified about
    // new private group chats for example (unless they get added).
    if (page.meta.pageType.isPrivateGroupTalk)
      return

    // ----- Parent category

    val notfPrefsOnCategory = page.categoryId.map(tx.loadPageNotfPrefsOnCategory) getOrElse Nil
    makeNewPostSubscrNotfFor(
          notfPrefsOnCategory, newPost, isEmbDiscFirstReply, minNotfLevel, memberIdsHandled)

    // ----- Grandparent category? [subcats]

    // ----- Tags

    // notPrefsOnTags = ... (later)
    //makeNewPostSubscrNotfFor(
    //     notPrefsOnTags, NotificationType.PostTagged, newPost, isEmbCommFirstReply ...)

    // ----- Whole site

    val notfPrefsOnSite = tx.loadPageNotfPrefsOnSite()
    makeNewPostSubscrNotfFor(
          notfPrefsOnSite, newPost, isEmbDiscFirstReply, minNotfLevel, memberIdsHandled)
  }


  /*
  def generateForDeletedPost(page: Page, post: Post, skipMentions: Boolean): Notifications = {
    dieIf(!skipMentions, "EsE6YKG567", "Unimplemented: deleting mentions")
    anyAuthor = Some(tx.loadTheUser(post.createdById))
    Notifications(
      toDelete = Seq(NotificationToDelete.NewPostToDelete(tx.siteId, post.uniqueId)))
  }*/


  /** Private messages are sent to all toUserIds, but not to any user mentioned in the
    * message.
    */
  def generateForMessage(sender: Participant, pageBody: Post, toUserIds: Set[UserId])
        : Notifications = {
    unimplementedIf(pageBody.approvedById.isEmpty, "Unapproved private message? [EsE7MKB3]")
    anyAuthor = Some(tx.loadTheParticipant(pageBody.createdById))
    tx.loadParticipants(toUserIds.filter(_ != sender.id)) foreach { user =>
      makeAboutPostNotfs(
          // But what if is 2 ppl chat — then would want to incl 1st message instead.
          NotificationType.Message, pageBody, inCategoryId = None, user)
    }
    generatedNotifications
  }


  private def makeAboutPostNotfs(
        notfType: NotificationType,
        post: Post,
        inCategoryId: Option[CategoryId],
        sendTo: Participant,
        sentFrom: Option[Participant] = None, // default is post author
        minNotfLevel: NotfLevel = NotfLevel.Hushed): Unit = {

    // legacy variable names CLEAN_UP but not now
    val toUserMaybeGroup = sendTo
    val newPost = post

    if (sentToUserIds.contains(toUserMaybeGroup.id))
      return

    if (toUserMaybeGroup.isGuest) {
      if (toUserMaybeGroup.emailNotfPrefs == EmailNotfPrefs.DontReceive ||
          toUserMaybeGroup.emailNotfPrefs == EmailNotfPrefs.ForbiddenForever ||
          toUserMaybeGroup.email.isEmpty) {
        return
      }
    }

    // Access control.
    // Sync w [2069RSK25]. Test: [2069RSK25-A]
    // (If this is a group and it may not see the post, then don't generate any
    // notfs on behalf of this group, even if there're individual group *members*
    // who may see the post (because of other groups they're in). [5AKTG7374])
    val pageMeta = tx.loadPageMeta(newPost.pageId) getOrDie "TyE05WKSJF3"
    val (maySeePost, whyNot) = dao.maySeePost(newPost, Some(toUserMaybeGroup),
        maySeeUnlistedPages = true)(tx)
    if (!maySeePost.may)
      return

    val (toUserIds: Set[UserId], moreExactNotfType) =
      if (!toUserMaybeGroup.isGroup) {
        (Set(toUserMaybeGroup.id), notfType)
      }
      else {
        // Is a group mention / a reply to a post by a group.

        val isMention = notfType == NotificationType.Mention
        val groupId = toUserMaybeGroup.id

        throwForbiddenIf(isMention && groupId == Group.EveryoneId,
          "TyEBDGRPMT01", s"May not mention ${toUserMaybeGroup.idSpaceName}")

        if (isMention && !mayMentionGroups(author)) {
          // For now, may still mention core members, staff and admins, so can ask how the site works.
          throwForbiddenIf(
            groupId < Group.CoreMembersId || Group.AdminsId < groupId,
              "TyEM0MNTNGRPS", s"You may not mention groups: ${toUserMaybeGroup.idSpaceName}")
        }

        // Generate a notf to the group, so will appear in its user profile.
        if (!sentToUserIds.contains(groupId)) {
          sentToUserIds += groupId
          notfsToCreate += Notification.NewPost(
            notfType,
            id = bumpAndGetNextNotfId(),
            createdAt = newPost.createdAt,
            uniquePostId = newPost.id,
            byUserId = newPost.createdById,
            toUserId = groupId)
        }

        // Find ids of group members to notify, and excl the sender henself:  (5ABKRW2)

        var groupMembers = tx.loadGroupMembers(groupId).filter(_.id != newPost.createdById)

        dieIf(groupMembers.exists(_.isGuest), "TyE7ABK402")

        // If loading e.g. the AllMembers group, all higher trust level groups get loaded too,
        // because they're members of the AllMembers group. [NESTDGRPS]
        groupMembers = groupMembers.filter(!_.isGroup)
        // Alternatively:
        /*
        groupMembers.find(_.isGroup).foreach(group =>
          throwForbidden("TyERECGRPMNT", o"""s$siteId: Notifications to groups in groups not implemented:
              user ${group.idSpaceName} is a group."""))
         */

        UX; COULD // add text: "@the_mention (not notified: too many people in group)"; throw no error.
        val maxMentions = config.maxGroupMentionNotfs
        throwForbiddenIf(isMention && groupMembers.size > maxMentions, "TyEMNYMBRS",
          s"${groupMembers.size} group members — but may not group-mention more than $maxMentions")

        val memberIds = groupMembers.map(_.id).toSet

        // UX SHOULD use a group notf type instead, it'll look a bit different: look less important.
        (memberIds, notfType)
      }

    for {
      toUserId <- toUserIds
      if toUserId <= MaxGuestId || Participant.LowestNormalMemberId <= toUserId
      if !sentToUserIds.contains(toUserId)
    } {
      // Generate notifications, regardless of email settings, so shown in the user's inbox.
      // We won't send any *email* though, if the user has unsubscribed from such emails.

      // Look at the user's notf level, to find out if hen has muted notifications,
      // on the current page / category / whole site.
      BUG; SHOULD // also consider ancestor group notf levels — maybe a group hen is in,
      // has muted the topic? Then, should generate no notf (unless the user henself has
      // un-muted the topic).
      // Or the user has muted the category, but a group hen is in, has unmuted this topic?
      // (Then, since topics are more specific, structure wise, we should generate a notf.)

      COULD; NotfLevel.Hushed // Also consider the type of notf: is it a direct message? Then send
      // if >= Hushed. If is a subthread indirect reply? Then don't send if == Hushed.

      val notfLevels = tx.loadPageNotfLevels(toUserId, newPost.pageId, inCategoryId)
      val usersMoreSpecificLevel =
        notfLevels.forPage.orElse(notfLevels.forCategory).orElse(notfLevels.forWholeSite)
      val skipBecauseMuted = usersMoreSpecificLevel is NotfLevel.Muted
      val skipBecauseHushed = (usersMoreSpecificLevel is NotfLevel.Hushed) &&
              notfType == NotificationType.IndirectReply
      if (!skipBecauseMuted && !skipBecauseHushed) {
        sentToUserIds += toUserId
        notfsToCreate += Notification.NewPost(
              notfType,
              id = bumpAndGetNextNotfId(),
              createdAt = newPost.createdAt,
              uniquePostId = newPost.id,
              byUserId = sentFrom.map(_.id) getOrElse newPost.createdById,
              toUserId = toUserId,
              emailStatus = emailStatusFor(toUserId))
      }
    }
  }


  /** Generates notfs for one content structure level. E.g. users or groups who have
    * subscribed to 1) a *page*. Or those who have subscribed to pages in 2) a *category*.
    * Or to 3) pages tagged with some certain tag(s). Or 4) *the whole site*.
    */
  private def makeNewPostSubscrNotfFor(notfPrefs: Seq[PageNotfPref], newPost: Post,
        isEmbDiscFirstReply: Boolean, minNotfLevel: NotfLevel,
        memberIdsHandled: mutable.Set[UserId]): Unit = {

    val membersById = tx.loadParticipantsAsMap(notfPrefs.map(_.peopleId))
    val memberIdsHandlingNow = mutable.HashSet[MemberId]()

    // Sync w [2069RSK25].  Test: [2069RSK25-B]
    val pageMeta = tx.loadPageMeta(newPost.pageId) getOrDie "TyE05WKSJF2"
    def maySeePost(ppt: Participant): Boolean = {
      val (maySee, whyNot) = dao.maySeePost(
          newPost, Some(ppt), maySeeUnlistedPages = true)(tx)
      maySee.may
    }

    // Individual users' preferences override group preferences, on the same
    // specificity level (prefs per page,  or per category,  or whole site).
    for {
      notfPref: PageNotfPref <- notfPrefs
      member <- membersById.get(notfPref.peopleId)
      maySee = maySeePost(member)
      if maySee
    } {
      if (debiki.Globals.isDevOrTest) {
        // A member can have only one notf pref per page or category or whole site.
        // (The pagenotfprefs_pageid_people_u and pagenotfprefs_category_people_u constraints.)
        val numPrefsThisMember = notfPrefs.count(_.peopleId == member.id)
        assert(numPrefsThisMember == 1,
            s"s$siteId: Bad num notf prefs: $numPrefsThisMember, member: $member")
      }
      maybeMakeNotfs(member, notfPref)
    }

    memberIdsHandled ++= memberIdsHandlingNow

    for {
      notfPref: PageNotfPref <- notfPrefs
      maybeGroup <- membersById.get(notfPref.peopleId)
      if maybeGroup.isGroup
      group = maybeGroup
      groupMaySee = maySeePost(group)
      if groupMaySee  // or ...
      groupMembers = tx.loadGroupMembers(group.id)
      member <- groupMembers
      // ... or what if a group has enabled site wide notfs, and cannot see category C,
      // but user U is in that group *can* see C (because of other group hen is in)
      // — then, should U get notified about topics in C or not?
      // For now: No. Let group notf settings affect only categories the group itself
      // can see (rather than what the group members can see,
      // — which might be more than what the group can see). I think it'd be a bit
      // unexpected if changing a group's notf settings, affects categories that
      // are listed as cannot-see on the group's page?
      // So skip this: [5AKTG7374]
      //   memberMaySee = maySeePost(member)
      //   if groupMaySee || memberMaySee
    } {
      maybeMakeNotfs(member, notfPref)
    }

    def maybeMakeNotfs(member: Participant, notfPref: PageNotfPref): Unit = {
      // If the member has already been considered, at a more specific content structure specificity,
      // then skip it here. For example, if it has configured a per page notf pref, then, skip it,
      // when considering categories and tags — because per page prefs are more specific.
      // However, do consider the member, if it occurs again, for different notf prefs, at the
      // same structure specificity. For example, if one category notf pref, from one group, says
      // Muted, and another category notf pref from another group, says EveryPost — then the
      // more chatty setting (EveryPost), wins. [CHATTYPREFS]
      if (memberIdsHandled.contains(member.id))
        return

      memberIdsHandlingNow += member.id

      if (notfPref.notfLevel.toInt < minNotfLevel.toInt) {
        // Example: A group has Muted a category. And this is a new comment, posted on a page
        // in that category, that `member` won't get notified about (unless there're other
        // notf prefs that says the group members *should* get notified).
        return
      }
      if (member.id == newPost.createdById)
        return
      if (member.isGone)
        return
      if (sentToUserIds.contains(member.id))
        return

      sentToUserIds += member.id
      notfsToCreate += Notification.NewPost(
        // UX maybe a NotificationType.NewPage instead? Especially if: isEmbDiscFirstReply.
        NotificationType.NewPost,
        id = bumpAndGetNextNotfId(),
        createdAt = newPost.createdAt,
        uniquePostId = newPost.id,
        byUserId = newPost.createdById,
        toUserId = member.id,
        emailStatus = emailStatusFor(member.id))
    }

    memberIdsHandled ++= memberIdsHandlingNow
  }


  private def emailStatusFor(userId: UserId): NotfEmailStatus =
    if (avoidDuplEmailToUserIds.contains(userId)) NotfEmailStatus.Skipped
    else NotfEmailStatus.Undecided


  /** Creates and deletes mentions, if '@username's are added/removed by this edit.
    */
  def generateForEdits(oldPost: Post, newPost: Post, anyNewSourceAndHtml: Option[SourceAndHtml])
        : Notifications = {

    BUG; SHOULD; REFACTOR // [5BKR03] Load users already mentioned — from the database, not
    // the old post text. Someone might have changed hens username, so looking at the old post text,
    // won't work. Then find current (after edits) people group mentioned, and mentioned directly.
    // Those mentioned directly now, but not before:
    //   Delete any previous group mentions. create direct mentions.
    //   (Repl group mentions, because direct mentions are (will be) shown with higher priority.)
    // Those mentioned directly now, and also before:
    //   Fine, needn't do anything.
    // Those group mentioned now:
    //   If mentioned directly, or group mentioned before: Fine, do nothing.
    //   Else, create a group mention.
    // Those no longer mentioned:
    //   Delete any old mention.

    require(oldPost.pagePostNr == newPost.pagePostNr, "TyE2WKA5LG")

    if (!newPost.isCurrentVersionApproved) {
      // Wait until the edits get approved and become visible.
      return Notifications.None
    }

    val anyNewTextAndHtml: Option[TextAndHtml] = anyNewSourceAndHtml map {
      case t: TextAndHtml => t
      case _: TitleSourceAndHtml =>
        // Currently titles cannot mention people, and editing it generates no notfs.
        // However, maybe later staff wants to get notified if titles of "important"
        // pages somehow get changed. For now, do nothing though. [no_title_notfs]
        return Notifications.None  // or: return generatedNotifications? the same?
    }

    anyAuthor = Some(tx.loadTheParticipant(newPost.createdById))

    anyNewTextAndHtml foreach { textAndHtml =>
      require(newPost.approvedSource is textAndHtml.text,
        s"approvedSource: ${newPost.approvedSource}, textAndHtml.text: ${textAndHtml.text} [TyE4WKB7Z]")
      require(newPost.approvedHtmlSanitized is textAndHtml.safeHtml,
        s"appr.HtmlSan.: ${newPost.approvedHtmlSanitized}, safeHtml: ${textAndHtml.safeHtml} [TyE4WB78]")
    }

    val oldMentions: Set[String] =
          findMentions(oldPost.approvedSource getOrDie "TyE0YKW3", site, nashorn)  // [nashorn_in_tx]

    val newMentions: Set[String] =
          anyNewTextAndHtml.map(_.usernameMentions) getOrElse findMentions(  // [nashorn_in_tx]
                newPost.approvedSource getOrDie "DwE2BF81", site, nashorn)

    val deletedMentions = oldMentions -- newMentions
    val createdMentions = newMentions -- oldMentions

    var mentionsDeletedForUsers = deletedMentions.flatMap(tx.loadMemberByUsername)
    var mentionsCreatedForUsers = createdMentions.flatMap(tx.loadMemberByUsername)

    val newMentionsIncludesAll = mentionsAllInChannel(newMentions)
    val oldMentionsIncludesAll = mentionsAllInChannel(oldMentions)

    lazy val mayAddGroup =
      mayMentionGroups(author)

    val mentionsForAllCreated = newMentionsIncludesAll && !oldMentionsIncludesAll && mayAddGroup
    val mentionsForAllDeleted = oldMentionsIncludesAll && !newMentionsIncludesAll
    dieIf(mentionsForAllCreated && mentionsForAllDeleted, "EdE2WK4Q0")

    lazy val previouslyMentionedUserIds: Set[UserId] =
      tx.loadNotificationsAboutPost(newPost.id, NotificationType.Mention).map(_.toUserId).toSet

    if (mentionsForAllDeleted) {
      // CLEAN_UP COULD simplify this whole function — needn't load mentionsDeletedForUsers above.
      val usersMentionedAfter = newMentions.flatMap(tx.loadUserByPrimaryEmailOrUsername)
      val toDelete: Set[UserId] = previouslyMentionedUserIds -- usersMentionedAfter.map(_.id)
      // (COULD_OPTIMIZE: needn't load anything here — we have the user ids already.)
      mentionsDeletedForUsers = tx.loadUsersAsMap(toDelete).values.toSet
    }

    if (mentionsForAllCreated) {
      val pageMemberIds: Set[UserId] = tx.loadMessageMembers(newPost.pageId)
      mentionsDeletedForUsers = mentionsDeletedForUsers.filterNot(u => pageMemberIds.contains(u.id))
      BUG; REFACTOR // [5BKR03] in rare cases, people might get two notfs: if they're a page member,
      // and also if they're in a group that gets @group_mentioned now, when editing.
      val moreToAdd: Set[UserId] =
        pageMemberIds -- previouslyMentionedUserIds -- mentionsCreatedForUsers.map(_.id)
      mentionsCreatedForUsers ++= tx.loadUsersAsMap(moreToAdd).values.toSet
    }

    // Delete mentions.
    for (user <- mentionsDeletedForUsers) {
      notfsToDelete += NotificationToDelete.ToOneMember(
            siteId = tx.siteId,
            uniquePostId = newPost.id,
            toUserId = user.id,
            NotificationType.Mention)
    }

    val pageMeta = tx.loadPageMeta(newPost.pageId)

    // Create mentions.
    for {
      user <- mentionsCreatedForUsers
      if user.id != newPost.createdById
    } {
      BUG // harmless. might mention people again, if previously mentioned directly,
      // and now again via a @group_mention. See REFACTOR above.
      makeAboutPostNotfs(
            NotificationType.Mention, newPost,
            inCategoryId = pageMeta.flatMap(_.categoryId), user)
    }

    generatedNotifications
  }


  def generateForLikeVote(post: Post, upvotedPostAuthor: Participant,
          voter: Participant, inCategoryId: Option[CategoryId]): Notifications = {
    if (upvotedPostAuthor.isGone || upvotedPostAuthor.isBuiltIn)
      return generatedNotifications

    if (upvotedPostAuthor.isGroup) {
      // Not implemented. What'd make sense to do? Notify everyone in the group,
      // or would that be too noisy?
      return generatedNotifications
    }

    makeAboutPostNotfs(
          NotificationType.OneLikeVote, post, inCategoryId = inCategoryId,
          sendTo = upvotedPostAuthor, sentFrom = Some(voter))

    generatedNotifications
  }


  def generateForTags(post: Post, tagsAdded: Set[TagLabel]): Notifications = {
    val userIdsWatching = tx.listUsersWatchingTags(tagsAdded)
    val userIdsNotified = tx.listUsersNotifiedAboutPost(post.id)
    val userIdsToNotify = userIdsWatching -- userIdsNotified
    val usersToNotify = tx.loadParticipants(userIdsToNotify.to[immutable.Seq])
    val pageMeta = tx.loadPageMeta(post.pageId)
    anyAuthor = Some(tx.loadTheParticipant(post.createdById))
    for {
      user <- usersToNotify
      if user.id != post.createdById
    } {
      // This is about the new (from the notf recipient's point of view) post,
      // so the notf is from the post author — also if someone else added the tag.
      makeAboutPostNotfs(
            NotificationType.PostTagged, post,
            inCategoryId = pageMeta.flatMap(_.categoryId),
            sendTo = user)
    }
    generatedNotifications
  }


  private def bumpAndGetNextNotfId(): NotificationId = {
    nextNotfId match {
      case None =>
        nextNotfId = Some(tx.nextNotificationId())
      case Some(id) =>
        nextNotfId = Some(id + 1)
    }
    nextNotfId getOrDie "EsE5GUY2"
  }

}


object NotificationGenerator {

  def mentionsAllInChannel(mentions: Set[String]): Boolean =
    mentions.contains("all") || mentions.contains("channel")


  def mayMentionGroups(user: Participant): Boolean = {
    user.isStaffOrMinTrustNotThreat(TrustLevel.BasicMember)
  }

  // Keep this regex in sync with mentions-markdown-it-plugin.js, the mentionsRegex [4LKBG782].
  // COULD replace [^a-zA-Z0-9_] with some Unicode regex for Unicode whitespace,
  // however apparently Java whitespace regex doesn't work:
  // https://stackoverflow.com/a/4731164/694469
  // — cannot deal with all Unicode whitespace. So just do [^a-z...] for now, so we for sure
  // allow *more* than the Js code. At least this should exclude email addresses.
  // (?s) makes '.' match newlines.
  private val MaybeMentionsRegex: Regex =
    "(?s)^(.*[^a-zA-Z0-9_])?@[a-zA-Z0-9_][a-zA-Z0-9_.-]*[a-zA-Z0-9].*".r  // [UNPUNCT]


  def findMentions(text: String, site: SiteIdHostnames, nashorn: Nashorn): Set[String] = {
    // Try to avoid rendering Commonmark source via Nashorn, if cannot possibly be any mentions:
    if (!MaybeMentionsRegex.matches(text))
      return Set.empty

    val result = nashorn.renderAndSanitizeCommonMark(
      // BUG? COULD incl origin here, so links won't be interpreted relative any
      // web browser client's address? — Right now, no images incl in reply notf emails
      // anyway, so need not fix now.
      text, site, embeddedOriginOrEmpty = "",
      allowClassIdDataAttrs = false, followLinks = false)

    result.mentions
  }

}
