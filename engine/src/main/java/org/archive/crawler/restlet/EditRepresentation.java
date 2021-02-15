/*
 *  This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual 
 *  contributors. 
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
 
package org.archive.crawler.restlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;

import org.apache.commons.lang.StringEscapeUtils;
import org.restlet.data.CharacterSet;
import org.restlet.data.MediaType;
import org.restlet.data.Reference;
import org.restlet.representation.CharacterRepresentation;
import org.restlet.representation.FileRepresentation;

/**
 * Representation wrapping a FileRepresentation, displaying its contents
 * in a TextArea for editing.
 * 
 * @author gojomo
 */
public class EditRepresentation extends CharacterRepresentation {
    protected FileRepresentation fileRepresentation;
    protected EnhDirectoryResource dirResource;

    public EditRepresentation(FileRepresentation representation, EnhDirectoryResource resource) {
        super(MediaType.TEXT_HTML);
        fileRepresentation = representation;
        dirResource = resource; 
        // TODO: remove if not necessary in future?
        setCharacterSet(CharacterSet.UTF_8);
    }

    @Override
    public Reader getReader() throws IOException {
        StringWriter writer = new StringWriter((int)fileRepresentation.getSize()+100);
        write(writer); 
        return new StringReader(writer.toString());
    }

    protected String getStaticRef(String resource) {
        String rootRef = dirResource.getRequest().getRootRef().toString();
        return rootRef + "/engine/static/" + resource;
    }

    @Override
    public void write(Writer writer) throws IOException {
        PrintWriter pw = new PrintWriter(writer); 
        pw.println("<!DOCTYPE html>");
        pw.println("<html>");
        pw.println("<head><title>"+fileRepresentation.getFile().getName()+"</title>");
//        pw.println("<link rel='stylesheet' href='" + getStaticRef("codemirror/codemirror.min.css") + "'>");
//        pw.println("<link rel='stylesheet' href='" + getStaticRef("codemirror/addon/dialog/dialog.min.css") + "'>");
//        pw.println("<script src='" + getStaticRef("codemirror/codemirror.min.js") + "'></script>");
//        pw.println("<script src='" + getStaticRef("codemirror/mode/xml/xml.min.js") + "'></script>");
//        pw.println("<script src='" + getStaticRef("codemirror/addon/dialog/dialog.min.js") + "'></script>");
//        pw.println("<script src='" + getStaticRef("codemirror/addon/search/search.min.js") + "'></script>");
//        pw.println("<script src='" + getStaticRef("codemirror/addon/search/searchcursor.min.js") + "'></script>");
//        pw.println("<style>.CodeMirror { background: #fff; }</style>");
        pw.println("</head>");
        pw.println("<body style='background-color:#ddd'>");
        pw.println("<form style='position:absolute;top:15px;bottom:15px;left:15px;right:15px;overflow:auto' method='POST' id=form>" +
                "<div id=\"container\" style=\"height:98%;border:1px solid black;\"></div>");
        pw.println("<textarea style='width:98%;height:90%;font-family:monospace' name='contents' id='editor'>");
        StringEscapeUtils.escapeHtml(pw,fileRepresentation.getText()); 
        pw.println("</textarea>");
        pw.println("<div id='savebar'>");
        pw.println("<input type='submit' value='save changes' id='savebutton'>");
        pw.println(fileRepresentation.getFile());
        Reference viewRef = dirResource.getRequest().getOriginalRef().clone(); 
        viewRef.setQuery(null);
        pw.println("<a href='"+viewRef+"'>view</a>");
        Flash.renderFlashesHTML(pw, dirResource.getRequest());
        pw.println("</div>");
        pw.println("</form>");
        pw.println("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.20.0/min/vs/loader.min.js\"></script>\n" +
                "<script>\n" +
                "let data = document.getElementById('editor').value;" +
                "// Based on https://jsfiddle.net/developit/bwgkr6uq/ which just works but is based on unpkg.com.\n" +
                "// Provided by loader.min.js.\n" +
                "require.config({ paths: { 'vs': 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.20.0/min/vs' }});\n" +
                "window.MonacoEnvironment = { getWorkerUrl: () => proxy };\n" +
                "let proxy = URL.createObjectURL(new Blob([`\n" +
                "    self.MonacoEnvironment = {\n" +
                "        baseUrl: 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.20.0/min'\n" +
                "    };\n" +
                "    importScripts('https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.20.0/min/vs/base/worker/workerMain.min.js');\n" +
                "`], { type: 'text/javascript' }));\n" +
                "require([\"vs/editor/editor.main\"], function () {\n" +
                "    let editor = monaco.editor.create(document.getElementById('container'), {\n" +
                "        value: data,\n" +
                "        language: 'xml',\n" +
//                "        theme: 'vs-dark'\n" +
                "    });\n" +
                "   document.getElementById('editor').outerHTML = '';" +
                "        const form = document.getElementById(\"form\");\n" +
                "        form.addEventListener(\"formdata\", e => {\n" +
                "            e.formData.append('contents', editor.getModel().getValue());\n" +
                "        });" +
                "});\n" +
                "</script>");
        pw.println("</body>");
        pw.println("</html>");
    }

    public FileRepresentation getFileRepresentation() {
        return fileRepresentation;
    }
}
