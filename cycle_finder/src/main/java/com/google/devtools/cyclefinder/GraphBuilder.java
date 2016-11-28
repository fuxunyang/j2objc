/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.cyclefinder;

import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.devtools.j2objc.ast.AnonymousClassDeclaration;
import com.google.devtools.j2objc.ast.ClassInstanceCreation;
import com.google.devtools.j2objc.ast.CompilationUnit;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.jdt.BindingConverter;
import com.google.devtools.j2objc.util.ElementUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

/**
 * Builds the graph of possible references between types.
 *
 * @author Keith Stanger
 */
public class GraphBuilder {

  private final Map<String, TypeNode> allTypes = new HashMap<>();
  private final CaptureFields captureFields;
  private final NameList whitelist;
  private final ReferenceGraph graph = new ReferenceGraph();

  public GraphBuilder(CaptureFields captureFields, NameList whitelist) {
    this.captureFields = captureFields;
    this.whitelist = whitelist;
  }

  public GraphBuilder constructGraph() {
    addFieldEdges();
    addSubtypeEdges();
    addSuperclassEdges();
    addOuterClassEdges();
    // TODO(kstanger): Capture edges should be added before subtype edges.
    addAnonymousClassCaptureEdges();
    return this;
  }

  public ReferenceGraph getGraph() {
    return graph;
  }

  private void addEdge(Edge e) {
    if (!e.getOrigin().equals(e.getTarget())) {
      graph.addEdge(e);
    }
  }

  private void addNode(TypeNode node) {
    allTypes.put(node.getSignature(), node);
  }

  private void visitType(ITypeBinding type) {
    if (type == null) {
      return;
    }
    type = getElementType(type);
    if (allTypes.containsKey(type.getKey()) || type.isPrimitive() || type.isRawType()) {
      return;
    }
    if (hasNestedWildcard(type)) {
      // Avoid infinite recursion caused by nested wildcard types.
      return;
    }
    addNode(new TypeNode(type, getNameForType(type)));
    followType(type);
  }

  private void followType(ITypeBinding type) {
    visitType(type.getSuperclass());
    visitType(type.getDeclaringClass());
    for (IVariableBinding field : type.getDeclaredFields()) {
      ITypeBinding fieldType = field.getType();
      for (ITypeBinding typeParam : fieldType.getTypeArguments()) {
        visitType(typeParam);
      }
      visitType(fieldType);
    }
    for (ITypeBinding interfaze : type.getInterfaces()) {
      visitType(interfaze);
    }
  }

  private void addFieldEdges() {
    for (TypeNode node : allTypes.values()) {
      ITypeBinding type = node.getTypeBinding();
      for (IVariableBinding field : type.getDeclaredFields()) {
        VariableElement fieldE = BindingConverter.getVariableElement(field);
        ITypeBinding fieldType = getElementType(field.getType());
        TypeNode targetNode = allTypes.get(fieldType.getKey());
        if (targetNode != null
            && !whitelist.containsField(field)
            && !whitelist.containsType(fieldType)
            && !fieldType.isPrimitive()
            && !Modifier.isStatic(field.getModifiers())
            // Exclude self-referential fields. (likely linked DS or delegate pattern)
            && !type.isAssignmentCompatible(fieldType)
            && !ElementUtil.isWeakReference(fieldE)
            && !ElementUtil.isRetainedWithField(fieldE)) {
          addEdge(Edge.newFieldEdge(node, targetNode, field));
        }
      }
    }
  }

  private ITypeBinding getElementType(ITypeBinding type) {
    if (type.isArray()) {
      return type.getElementType();
    }
    return type;
  }

  private void addSubtypeEdges() {
    SetMultimap<TypeNode, TypeNode> subtypes = HashMultimap.create();
    for (TypeNode type : allTypes.values()) {
      collectSubtypes(type, type, subtypes);
    }
    for (TypeNode type : allTypes.values()) {
      for (Edge e : ImmutableList.copyOf(graph.getEdges(type))) {
        Set<TypeNode> targetSubtypes = subtypes.get(e.getTarget());
        Set<TypeNode> whitelisted = new HashSet<>();
        IVariableBinding field = e.getField();
        for (TypeNode subtype : targetSubtypes) {
          ITypeBinding subtypeBinding = subtype.getTypeBinding();
          if ((field != null && field.isField()
               && whitelist.isWhitelistedTypeForField(field, subtypeBinding))
              || whitelist.containsType(subtypeBinding)) {
            whitelisted.add(subtype);
            whitelisted.addAll(subtypes.get(subtype));
          }
        }
        for (TypeNode subtype : Sets.difference(targetSubtypes, whitelisted)) {
          addEdge(Edge.newSubtypeEdge(e, subtype));
        }
      }
    }
  }

  private void collectSubtypes(
      TypeNode originalType, TypeNode type, Multimap<TypeNode, TypeNode> subtypes) {
    ITypeBinding typeBinding = type.getTypeBinding();
    for (ITypeBinding interfaze : typeBinding.getInterfaces()) {
      TypeNode interfaceNode = allTypes.get(interfaze.getKey());
      if (interfaceNode != null) {
        subtypes.put(interfaceNode, originalType);
        collectSubtypes(originalType, interfaceNode, subtypes);
      }
    }
    if (typeBinding.getSuperclass() != null) {
      TypeNode superclassNode = allTypes.get(typeBinding.getSuperclass().getKey());
      if (superclassNode != null) {
        subtypes.put(superclassNode, originalType);
        collectSubtypes(originalType, superclassNode, subtypes);
      }
    }
  }

  private void addSuperclassEdges() {
    for (TypeNode type : allTypes.values()) {
      ITypeBinding superclass = type.getTypeBinding().getSuperclass();
      while (superclass != null) {
        TypeNode superclassNode = allTypes.get(superclass.getKey());
        for (Edge e : graph.getEdges(superclassNode)) {
          addEdge(Edge.newSuperclassEdge(e, type, superclassNode));
        }
        superclass = superclass.getSuperclass();
      }
    }
  }

  private void addOuterClassEdges() {
    for (TypeNode type : allTypes.values()) {
      ITypeBinding typeBinding = type.getTypeBinding();
      Element element = BindingConverter.getElement(typeBinding.getTypeDeclaration());
      if (ElementUtil.isTypeElement(element)
          && captureFields.hasOuterReference((TypeElement) element)
          && !ElementUtil.isWeakOuterType((TypeElement) element)) {
        ITypeBinding declaringType = typeBinding.getDeclaringClass();
        if (declaringType != null && !whitelist.containsType(declaringType)
            && !whitelist.hasOuterForType(typeBinding)) {
          addEdge(Edge.newOuterClassEdge(type, allTypes.get(declaringType.getKey())));
        }
      }
    }
  }

  private void addAnonymousClassCaptureEdges() {
    for (TypeNode type : allTypes.values()) {
      ITypeBinding typeBinding = type.getTypeBinding();
      if (typeBinding.isAnonymous()) {
        for (VariableElement capturedVarElement :
             captureFields.getCaptureFields(
                 BindingConverter.getTypeElement(typeBinding.getTypeDeclaration()))) {
          IVariableBinding capturedVarBinding = (IVariableBinding) BindingConverter.unwrapElement(
              capturedVarElement);
          ITypeBinding targetType = getElementType(capturedVarBinding.getType());
          if (!targetType.isPrimitive() && !whitelist.containsType(targetType)
              && !ElementUtil.isWeakReference(capturedVarElement)) {
            TypeNode target = allTypes.get(targetType.getKey());
            if (target != null) {
              addEdge(Edge.newCaptureEdge(
                  type, allTypes.get(targetType.getKey()), capturedVarBinding));
            }
          }
        }
      }
    }
  }

  private static String getNameForType(ITypeBinding type) {
    String name = type.getName();
    return Strings.isNullOrEmpty(name) ? type.getKey() : name;
  }

  private static boolean hasWildcard(ITypeBinding type) {
    if (type.isWildcardType()) {
      return true;
    }
    for (ITypeBinding typeParam : type.getTypeArguments()) {
      if (hasWildcard(typeParam)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasNestedWildcard(ITypeBinding type) {
    ITypeBinding bound = type.getBound();
    if (bound != null && hasWildcard(bound)) {
      return true;
    }
    for (ITypeBinding typeParam : type.getTypeArguments()) {
      if (hasNestedWildcard(typeParam)) {
        return true;
      }
    }
    return false;
  }

  public void visitAST(final CompilationUnit unit) {
    unit.accept(new TreeVisitor() {

      @Override
      public boolean visit(TypeDeclaration node) {
        ITypeBinding binding = BindingConverter.unwrapTypeElement(node.getTypeElement());
        addNode(new TypeNode(binding, getNameForType(binding)));
        followType(binding);
        return true;
      }

      @Override
      public boolean visit(AnonymousClassDeclaration node) {
        ITypeBinding binding = BindingConverter.unwrapTypeElement(node.getTypeElement());
        addNode(new TypeNode(binding, "anonymous:" + node.getLineNumber()));
        followType(binding);
        return true;
      }

      @Override
      public boolean visit(ClassInstanceCreation node) {
        visitType(BindingConverter.unwrapTypeMirrorIntoTypeBinding(node.getTypeMirror()));
        return true;
      }

      @Override
      public boolean visit(MethodInvocation node) {
        visitType(BindingConverter.unwrapTypeMirrorIntoTypeBinding(node.getTypeMirror()));
        return true;
      }
    });
  }
}