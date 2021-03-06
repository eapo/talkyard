
// Things in editor-bundle.js that we can access from outside the bundle,
// once it's been loaded already.

declare namespace debiki2.editor {

  function getOrCreateEditor(success: (editor: any) => void);

  function markdownToSafeHtml(source: string, hostAndPort?, sanitizerOptions?): string;

}

// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
