/**
 * beanxml.js
 *
 * Extends CodeMirror's xmlpure mode with extra state tracking in
 * order to show javadoc for Spring beans.
 *
 * Requires: xmlpure.js
 *
 * Example usage:
 *
 * {
 *     mode: {name: "beanxml"},
 *     extraKeys: {"F1": function(cm) { window.open(cm.javadoc(cm), "_blank"); }},
 *     javadocUrl: "http://www.example.com/javadoc/",
 * }
 *
 */

CodeMirror.defaults["javadocUrl"] = "http://builds.archive.org:8080/javadoc/heritrix-3.x-snapshot/";

CodeMirror.defineExtension("javadoc", function(cm) {
    var pos = cm.getCursor();
    var tok = cm.getTokenAt(pos);
    var state = tok.state;

    var ctx = state.xmlState.context;
    var beanClass = null;
    var beanProperty = null;

    // walk up the tag tree to find the bean or property we're inside
    while (ctx) {
	if (ctx.tagName == "bean" && ctx.attributes) {
	    beanClass = ctx.attributes["class"];
	    break;
	} else if (ctx.tagName == "property" && ctx.attributes) {
	    beanProperty = ctx.attributes["name"];
	}
	ctx = ctx.prev;
    }

    var docUrl = cm.getOption("javadocUrl") + beanClass.replace(/\./g, "/") + ".html";

    if (beanProperty) {
	docUrl += "#get" + beanProperty.charAt(0).toUpperCase() + beanProperty.substring(1) + "()";
    }

    return docUrl;
});

CodeMirror.defineMode("beanxml", function(config, parserConfig) {
  var xmlMode = CodeMirror.getMode(config, "xmlpure");

  function xml(stream, state) {
    var style = xmlMode.token(stream, state.xmlState);

    if (style == "attribute") {
	var token = stream.current();
	var attribute = token.substring(0, token.length - 1);
	var m = stream.match(/"([^"]*)"|'([^']*)'/, false);
	var value = m && (m[1] || m[2]);

	if (!state.xmlState.context.attributes) {
	    state.xmlState.context.attributes = {};
	}

	state.xmlState.context.attributes[attribute] = value;
    }

    return style;
  }

  return {
    startState: function() {
      var state = xmlMode.startState();
      return {token: xml, localState: null, mode: "xmlpure", xmlState: state};
    },

    copyState: function(state) {
      return {token: state.token, mode: state.mode,
              xmlState: CodeMirror.copyState(xmlMode, state.xmlState)};
    },

    token: function(stream, state) {
      return state.token(stream, state);
    },

    indent: function(state, textAfter) {
      return xmlMode.indent(state.xmlState, textAfter);
    },

    compareStates: function(a, b) {
      return xmlMode.compareStates(a.xmlState, b.xmlState);
    },
  }
});
