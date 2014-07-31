/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.ProcessCommonJSModules.FindGoogProvideOrGoogModule;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rewrites a ES6 module into a form that can be safely concatenated.
 * Note that we treat a file as an ES6 module if it has at least one import or
 * export statement.
 *
 * @author moz@google.com (Michael Zhou)
 */
public class ProcessEs6Modules extends AbstractPostOrderCallback {
  private static final String MODULE_SLASH = ES6ModuleLoader.MODULE_SLASH;
  public static final String DEFAULT_FILENAME_PREFIX =
      "." + ES6ModuleLoader.MODULE_SLASH;

  private static final String MODULE_NAME_SEPARATOR = "\\$";
  private static final String MODULE_NAME_PREFIX = "module$";

  private final ES6ModuleLoader loader;

  private final Compiler compiler;
  private int scriptNodeCount = 0;

  /**
   * Maps symbol names to their exported names.
   */
  private Map<String, String> exportMap = new LinkedHashMap<>();

  /**
   * Maps symbol names to a pair of <moduleName, originalName>. The original
   * name is the name of the symbol exported by the module. This is required
   * because we want to be able to update the original property on the module
   * object. Eg: "import {foo as f} from 'm'" maps 'f' to the pair <'m', 'foo'>.
   */
  private Map<String, ModuleOriginalNamePair> importMap = new HashMap<>();

  private Set<String> alreadyRequired = new HashSet<>();

  private boolean isEs6Module;

  ProcessEs6Modules(Compiler compiler, ES6ModuleLoader loader) {
    this.compiler = compiler;
    this.loader = loader;
  }

  public void processFile(Node root) {
    FindGoogProvideOrGoogModule finder = new FindGoogProvideOrGoogModule();
    NodeTraversal.traverse(compiler, root, finder);
    if (finder.isFound()) {
      return;
    }
    isEs6Module = false;
    NodeTraversal.traverse(compiler, root, this);
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (n.isImport()) {
      isEs6Module = true;
      visitImport(t, n, parent);
    } else if (n.isExport()) {
      isEs6Module = true;
      visitExport(t, n, parent);
    } else if (n.isScript()) {
      scriptNodeCount++;
      visitScript(t, n);
    }
  }

  private void visitImport(NodeTraversal t, Node n, Node parent) {
    String importName = n.getLastChild().getString();
    String loadAddress = loader.locate(importName, t.getInput());
    try {
      loader.load(loadAddress);
    } catch (ES6ModuleLoader.LoadFailedException e) {
      t.makeError(n, ES6ModuleLoader.LOAD_ERROR, importName);
    }

    String moduleName = toModuleName(loadAddress);
    for (Node child : n.children()) {
      if (child.isEmpty() || child.isString()) {
        continue;
      } else if (child.isName()) { // import a from "mod"
        importMap.put(child.getString(),
            new ModuleOriginalNamePair(moduleName, child.getString()));
      } else {
        for (Node grandChild : child.children()) {
          String origName = grandChild.getFirstChild().getString();
          if (grandChild.getChildCount() == 2) { // import {a as foo} from "mod"
            importMap.put(
                grandChild.getLastChild().getString(),
                new ModuleOriginalNamePair(moduleName, origName));
          } else { // import {a} from "mod"
            importMap.put(
                origName,
                new ModuleOriginalNamePair(moduleName, origName));
          }
        }
      }
    }
    // Emit goog.require call
    if (!alreadyRequired.contains(moduleName)) {
      alreadyRequired.add(moduleName);
      NodeUtil.getEnclosingType(parent, Token.SCRIPT).addChildToFront(
          IR.exprResult(IR.call(NodeUtil.newQualifiedNameNode(
              compiler.getCodingConvention(), "goog.require"),
              IR.string(moduleName))).copyInformationFromForTree(n));
    }
    parent.removeChild(n);
    compiler.reportCodeChange();
  }

  private void visitExport(NodeTraversal t, Node n, Node parent) {
    if (n.getBooleanProp(Node.EXPORT_DEFAULT)) {
      // TODO(moz): Handle default export: export default foo = 2
      compiler.report(JSError.make(n, Es6ToEs3Converter.CANNOT_CONVERT_YET,
          "Default export"));
    } else if (n.getBooleanProp(Node.EXPORT_ALL_FROM)) {
      // TODO(moz): Maybe support wildcard: export * from "mod"
      compiler.report(JSError.make(n, Es6ToEs3Converter.CANNOT_CONVERT_YET,
          "Wildcard export"));
    } else {
      if (n.getChildCount() == 2) {
        // TODO(moz): Support export FromClause.
        compiler.report(JSError.make(n, Es6ToEs3Converter.CANNOT_CONVERT_YET,
            "Export with FromClause"));
        return;
      }

      if (n.getFirstChild().getType() == Token.EXPORT_SPECS) {
        for (Node grandChild : n.getFirstChild().children()) {
          Node origName = grandChild.getFirstChild();
          exportMap.put(
              grandChild.getChildCount() == 2
                  ? grandChild.getLastChild().getString()
                  : origName.getString(),
              origName.getString());
        }
        parent.removeChild(n);
      } else {
        for (Node grandChild : n.getFirstChild().children()) {
          if (!grandChild.isName()) {
            break;
          }
          String name = grandChild.getString();
          Var v = t.getScope().getVar(name);
          if (v == null || v.isGlobal()) {
            exportMap.put(name, name);
          }
        }
        parent.replaceChild(n, n.removeFirstChild());
      }
      compiler.reportCodeChange();
    }
  }

  private void visitScript(NodeTraversal t, Node script) {
    if (!isEs6Module) {
      return;
    }
    Preconditions.checkArgument(scriptNodeCount == 1,
        "ProcessEs6Modules supports only one invocation per "
        + "CompilerInput / script node");

    String moduleName = toModuleName(loader.getLoadAddress(t.getInput()));

    // Rename vars to not conflict in global scope.
    NodeTraversal.traverse(compiler, script, new RenameGlobalVars(moduleName));

    if (exportMap.isEmpty()) {
      return;
    }

    // Creates an export object for this module.
    // var moduleName = { foo: moduleName$$foo };
    Node objectlit = IR.objectlit();
    for (String name : exportMap.keySet()) {
      objectlit.addChildToBack(
          IR.stringKey(name, IR.name(exportMap.get(name) + "$$" + moduleName)));
    }
    Node varNode = IR.var(IR.name(moduleName), objectlit)
        .copyInformationFromForTree(script);
    script.addChildToBack(varNode);

    exportMap.clear();

    // Add goog.provide call.
    script.addChildToFront(IR.exprResult(
        IR.call(NodeUtil.newQualifiedNameNode(
            compiler.getCodingConvention(), "goog.provide"),
            IR.string(moduleName))).copyInformationFromForTree(script));
    compiler.reportCodeChange();
  }

  /**
   * Turns a filename into a JS identifier that is used for moduleNames in
   * rewritten code. For example, "./foo.js" transformed to "foo".
   */
  public static String toModuleName(String filename) {
    return MODULE_NAME_PREFIX
        + filename.replaceAll("^\\." + Pattern.quote(MODULE_SLASH), "")
            .replaceAll(Pattern.quote(MODULE_SLASH), MODULE_NAME_SEPARATOR)
            .replaceAll(Pattern.quote("\\"), MODULE_NAME_SEPARATOR)
            .replaceAll("\\.js$", "")
            .replaceAll("-", "_")
            .replaceAll("\\.", "");
  }

  /**
   * Traverses a node tree and
   * 1. Appends a suffix to all global variable names defined in this module.
   * 2. Changes references to imported values to be property accesses on the
   *    imported module object.
   */
  private class RenameGlobalVars extends AbstractPostOrderCallback {
    private final String suffix;

    RenameGlobalVars(String suffix) {
      this.suffix = suffix;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      JSDocInfo info = n.getJSDocInfo();
      if (info != null) {
        for (Node typeNode : info.getTypeNodes()) {
          fixTypeNode(t, typeNode);
        }
      }

      if (n.isName()) {
        String name = n.getString();
        if (suffix.equals(name)) {
          return;
        }

        Scope.Var var = t.getScope().getVar(name);
        if (var != null && var.isGlobal()) {
          // Avoid polluting the global namespace.
          n.setString(name + "$$" + suffix);
          n.putProp(Node.ORIGINALNAME_PROP, name);
        } else if (var == null && importMap.containsKey(name)) {
          // Change to property access on the imported module object.
          if (parent.isCall() && parent.getFirstChild() == n) {
            parent.putBooleanProp(Node.FREE_CALL, false);
          }
          ModuleOriginalNamePair pair = importMap.get(name);
          n.getParent().replaceChild(n,
              IR.getprop(IR.name(pair.module), IR.string(pair.originalName))
              .useSourceInfoIfMissingFromForTree(n));
        }
      }
    }

    /**
     * Replace type name references. Change short names to fully qualified names
     * with namespace prefixes. Eg: {Foo} becomes {module$test.Foo}.
     */
    private void fixTypeNode(NodeTraversal t, Node typeNode) {
      if (typeNode.isString()) {
        String name = typeNode.getString();
        if (ES6ModuleLoader.isRelativeIdentifier(name)) {
          int lastSlash = name.lastIndexOf("/");
          int endIndex = name.indexOf('.', lastSlash);
          String localTypeName = null;
          if (endIndex == -1) {
            endIndex = name.length();
          } else {
            localTypeName = name.substring(endIndex);
          }

          String moduleName = name.substring(0, endIndex);
          String loadAddress = loader.locate(moduleName, t.getInput());
          if (loadAddress == null) {
            t.makeError(typeNode, ES6ModuleLoader.LOAD_ERROR, moduleName);
            return;
          }

          String globalModuleName = toModuleName(loadAddress);
          typeNode.setString(
              localTypeName == null
                  ? globalModuleName
                  : globalModuleName + localTypeName);
        } else {
          List<String> splitted = Splitter.on('.').limit(2).splitToList(name);
          String baseName = splitted.get(0);
          String rest = "";
          if (splitted.size() == 2) {
            rest = "." + splitted.get(1);
          }
          Scope.Var var = t.getScope().getVar(baseName);
          if (var != null && var.isGlobal()) {
            typeNode.setString(baseName + "$$" + suffix + rest);
          } else if (var == null && importMap.containsKey(name)) {
            ModuleOriginalNamePair pair = importMap.get(baseName);
            typeNode.setString(pair.module + "." + pair.originalName);
          }
          typeNode.putProp(Node.ORIGINALNAME_PROP, name);
        }
      }

      for (Node child = typeNode.getFirstChild(); child != null;
           child = child.getNext()) {
        fixTypeNode(t, child);
      }
      compiler.reportCodeChange();
    }
  }

  private class ModuleOriginalNamePair {
    private String module;
    private String originalName;

    private ModuleOriginalNamePair(String module, String originalName) {
      this.module = module;
      this.originalName = originalName;
    }
  }
}
