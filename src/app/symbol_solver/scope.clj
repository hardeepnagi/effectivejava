(ns app.symbol_solver.scope
  (:use [app.javaparser])
  (:use [app.operations])
  (:use [app.itemsOnLifecycle])
  (:use [app.utils])
  (:use [app.symbol_solver.type_solver])
  (:require [instaparse.core :as insta])
  (:import [app.operations Operation])
  (:use [app.symbol_solver.protocols]))

(import com.github.javaparser.JavaParser)
(import com.github.javaparser.ast.CompilationUnit)
(import com.github.javaparser.ast.Node)
(import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)
(import com.github.javaparser.ast.body.EnumDeclaration)
(import com.github.javaparser.ast.body.EnumConstantDeclaration)
(import com.github.javaparser.ast.body.ConstructorDeclaration)
(import com.github.javaparser.ast.body.FieldDeclaration)
(import com.github.javaparser.ast.body.MethodDeclaration)
(import com.github.javaparser.ast.body.ModifierSet)
(import com.github.javaparser.ast.body.TypeDeclaration)
(import com.github.javaparser.ast.body.VariableDeclaratorId)
(import com.github.javaparser.ast.stmt.ExpressionStmt)
(import com.github.javaparser.ast.stmt.BlockStmt)
(import com.github.javaparser.ast.expr.MethodCallExpr)
(import com.github.javaparser.ast.expr.NameExpr)
(import com.github.javaparser.ast.expr.IntegerLiteralExpr)
(import com.github.javaparser.ast.expr.AssignExpr)
(import com.github.javaparser.ast.expr.VariableDeclarationExpr)
(import com.github.javaparser.ast.body.VariableDeclarator)
(import com.github.javaparser.ast.body.VariableDeclaratorId)
(import com.github.javaparser.ast.visitor.DumpVisitor)
(import com.github.javaparser.ast.type.PrimitiveType)

(defprotocol scope
  ; for example in a BlockStmt containing statements [a b c d e], when solving symbols in the context of c
  ; it will contains only statements preceeding it [a b]
  (solveSymbol [this context nameToSolve])
  (solveClass [this context nameToSolve]))

(extend-protocol scope
  com.github.javaparser.ast.body.FieldDeclaration
  (solveSymbol [this context nameToSolve]
    (let [variables (.getVariables this)
          solvedSymbols (map (fn [c] (solveSymbol c nil nameToSolve)) variables)
          solvedSymbols' (remove nil? solvedSymbols)]
      (first solvedSymbols'))))

(extend-protocol scope
  NameExpr
  (solveSymbol [this context nameToSolve]
    (when-not context
      (solveSymbol (.getParentNode this) nil nameToSolve))))

(extend-protocol scope
  AssignExpr
  (solveSymbol [this context nameToSolve]
    (if context
      (or (solveSymbol (.getTarget this) this nameToSolve) (solveSymbol (.getValue this) this nameToSolve))
      (solveSymbol (.getParentNode this) this nameToSolve))))

(extend-protocol scope
  IntegerLiteralExpr
  (solveSymbol [this context nameToSolve] nil))

(extend-protocol scope
  BlockStmt
  (solveSymbol [this context nameToSolve]
    (let [elementsToConsider (if (nil? context) (.getStmts this) (preceedingChildren (.getStmts this) context))
          solvedSymbols (map (fn [c] (solveSymbol c nil nameToSolve)) elementsToConsider)
          solvedSymbols' (remove nil? solvedSymbols)]
      (or (first solvedSymbols') (solveSymbol (.getParentNode this) this nameToSolve)))))

(extend-protocol scope
  ExpressionStmt
  (solveSymbol [this context nameToSolve]
    (let [fromExpr (solveSymbol (.getExpression this) this nameToSolve)]
      (or fromExpr (solveSymbol (.getParentNode this) this nameToSolve)))))

(extend-protocol scope
  VariableDeclarationExpr
  (solveSymbol [this context nameToSolve]
    (first (filter (fn [s] (not (nil? (solveSymbol s this nameToSolve)))) (.getVars this)))))

(extend-protocol scope
  VariableDeclarator
  (solveSymbol [this context nameToSolve]
    (solveSymbol (.getId this) nil nameToSolve)))

(extend-protocol scope
  VariableDeclaratorId
  (solveSymbol [this context nameToSolve]
    (when (= nameToSolve (.getName this))
      this)))

(defn solveClassInPackage [pakage nameToSolve]
  {:pre [typeSolver]}
  ; TODO first look into the package
  (typeSolver nameToSolve))

(defn- solveAmongDeclaredFields [this nameToSolve]
  (let [members (.getMembers this)
        declaredFields (filter (partial instance? com.github.javaparser.ast.body.FieldDeclaration) members)
        solvedSymbols (map (fn [c] (solveSymbol c nil nameToSolve)) declaredFields)
        solvedSymbols' (remove nil? solvedSymbols)]
    (first solvedSymbols')))

(extend-protocol scope
  com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
  (solveSymbol [this context nameToSolve]
    (let [amongDeclaredFields (solveAmongDeclaredFields this nameToSolve)]
      (if (and (nil? amongDeclaredFields) (not (.isInterface this)) (not (empty? (.getExtends this))))
        (let [superclass (first (.getExtends this))
              superclassName (.getName superclass)
              superclassDecl (solveClass this this superclassName)]
          (if (nil? superclassDecl)
            (throw (RuntimeException. (str "Superclass not solved: " superclassName)))
            (let [inheritedFields (allFields superclassDecl)
                  solvedSymbols'' (filter (fn [f] (= nameToSolve (fieldName f))) inheritedFields)]
              (first solvedSymbols''))))
        amongDeclaredFields)))
  (solveClass [this context nameToSolve]
    (solveClass (getCu this) nil nameToSolve)))

(extend-protocol scope
  com.github.javaparser.ast.body.MethodDeclaration
  (solveSymbol [this context nameToSolve]
    (solveSymbol (.getParentNode this) nil nameToSolve)))

(extend-protocol scope
  com.github.javaparser.ast.CompilationUnit
  ; TODO consider imports
  (solveClass [this context nameToSolve]
    (let [typesInCu (topLevelTypes this)
          compatibleTypes (filter (fn [t] (= nameToSolve (getName t))) typesInCu)]
      (or (first compatibleTypes) (solveClassInPackage (getClassPackage this) nameToSolve)))))