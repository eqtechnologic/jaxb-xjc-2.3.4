/*
 * Copyright (c) 1997, 2021 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package com.sun.tools.xjc.generator.bean.field;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.xml.bind.annotation.W3CDomHandler;
import javax.xml.bind.annotation.XmlList;
import javax.xml.bind.annotation.XmlMixed;
import javax.xml.bind.annotation.XmlNsForm;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.XmlInlineBinaryData;
import javax.xml.namespace.QName;

import com.sun.codemodel.JAnnotatable;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JType;
import com.sun.tools.xjc.generator.annotation.spec.XmlAnyElementWriter;
import com.sun.tools.xjc.generator.annotation.spec.XmlAttributeWriter;
import com.sun.tools.xjc.generator.annotation.spec.XmlElementRefWriter;
import com.sun.tools.xjc.generator.annotation.spec.XmlElementRefsWriter;
import com.sun.tools.xjc.generator.annotation.spec.XmlElementWriter;
import com.sun.tools.xjc.generator.annotation.spec.XmlElementsWriter;
import com.sun.tools.xjc.generator.annotation.spec.XmlSchemaTypeWriter;
import com.sun.tools.xjc.generator.bean.ClassOutlineImpl;
import com.sun.tools.xjc.model.CAttributePropertyInfo;
import com.sun.tools.xjc.model.CElement;
import com.sun.tools.xjc.model.CElementInfo;
import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.CReferencePropertyInfo;
import com.sun.tools.xjc.model.CTypeInfo;
import com.sun.tools.xjc.model.CTypeRef;
import com.sun.tools.xjc.model.CValuePropertyInfo;
import com.sun.tools.xjc.model.nav.NClass;
import com.sun.tools.xjc.outline.Aspect;
import static com.sun.tools.xjc.outline.Aspect.IMPLEMENTATION;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.FieldAccessor;
import com.sun.tools.xjc.outline.FieldOutline;
import com.sun.tools.xjc.reader.TypeUtil;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.api.SpecVersion;
import com.sun.xml.bind.api.impl.NameConverter;
import com.sun.xml.bind.v2.TODO;

/**
 * Useful base class for implementing {@link FieldOutline}.
 * 
 * <p>
 * This class just provides a few utility methods and keep some
 * important variables so that they can be readily accessed any time.
 *
 * @author
 *     Kohsuke Kawaguchi (kohsuke.kawaguchi@sun.com)
 */
abstract class AbstractField implements FieldOutline {
    
    protected final ClassOutlineImpl outline;
    
    protected final CPropertyInfo prop;
    
    protected final JCodeModel codeModel;

    /**
     * The type of this field, which can hold all the possible types.
     */
    protected final JType implType;

    /**
     * The publicly visible type of this field.
     * If we are generating value classes implType==exposedType.
     */
    protected final JType exposedType;

    protected AbstractField( ClassOutlineImpl outline, CPropertyInfo prop ) {
        this.outline = outline;
        this.prop = prop;
        this.codeModel = outline.parent().getCodeModel();
        this.implType = getType(IMPLEMENTATION);
        this.exposedType = getType(Aspect.EXPOSED);
    }

    public final ClassOutline parent() {
        return outline;
    }

    public final CPropertyInfo getPropertyInfo() {
        return prop;
    }


    /**
     * Annotate the field according to the recipes given as {@link CPropertyInfo}.
     */
    protected void annotate( JAnnotatable field ) {

        assert(field!=null);

        /*
        TODO: consider moving this logic to somewhere else
        so that it can be better shared, for how a field gets
        annotated doesn't really depend on how we generate accessors.

        so perhaps we should separate those two.
        */

        // TODO: consider a visitor
        if (prop instanceof CAttributePropertyInfo) {
            annotateAttribute(field);
        } else if (prop instanceof CElementPropertyInfo) {
            annotateElement(field);
        } else if (prop instanceof CValuePropertyInfo) {
            field.annotate(XmlValue.class);
        } else if (prop instanceof CReferencePropertyInfo) {
            annotateReference(field);
        }

        outline.parent().generateAdapterIfNecessary(prop,field);

        QName st = prop.getSchemaType();
        if(st!=null)
            field.annotate2(XmlSchemaTypeWriter.class)
                .name(st.getLocalPart())
                .namespace(st.getNamespaceURI());

        if(prop.inlineBinaryData())
            field.annotate(XmlInlineBinaryData.class);
    }

    private void annotateReference(JAnnotatable field) {
        CReferencePropertyInfo rp = (CReferencePropertyInfo) prop;

        TODO.prototype();
        // this is just a quick hack to get the basic test working

        Collection<CElement> elements = rp.getElements();

        XmlElementRefWriter refw;
        if(elements.size()==1) {
            refw = field.annotate2(XmlElementRefWriter.class);
            CElement e = elements.iterator().next();
            refw.name(e.getElementName().getLocalPart())
                .namespace(e.getElementName().getNamespaceURI())
                .type(e.getType().toType(outline.parent(),IMPLEMENTATION));
            if(getOptions().target.isLaterThan(SpecVersion.V2_2))
                refw.required(rp.isRequired());
        } else
        if(elements.size()>1) {
            XmlElementRefsWriter refsw = field.annotate2(XmlElementRefsWriter.class);
            for( CElement e : elements ) {
                refw = refsw.value();
                refw.name(e.getElementName().getLocalPart())
                    .namespace(e.getElementName().getNamespaceURI())
                    .type(e.getType().toType(outline.parent(),IMPLEMENTATION));
                if(getOptions().target.isLaterThan(SpecVersion.V2_2))
                    refw.required(rp.isRequired());
            }
        }

        if(rp.isMixed())
            field.annotate(XmlMixed.class);

        NClass dh = rp.getDOMHandler();
        if(dh!=null) {
            XmlAnyElementWriter xaew = field.annotate2(XmlAnyElementWriter.class);
            xaew.lax(rp.getWildcard().allowTypedObject);

            final JClass value = dh.toType(outline.parent(),IMPLEMENTATION);
            if(!value.equals(codeModel.ref(W3CDomHandler.class))) {
                xaew.value(value);
            }
        }

    }

    /**
     * Annotate the element property 'field'
     */
    private void annotateElement(JAnnotatable field) {
        CElementPropertyInfo ep = (CElementPropertyInfo) prop;
        List<CTypeRef> types = ep.getTypes();

        if(ep.isValueList()) {
            field.annotate(XmlList.class);
        }

        assert ep.getXmlName()==null;
//        if( eName!=null ) { // wrapper
//            XmlElementWrapperWriter xcw = field.annotate2(XmlElementWrapperWriter.class);
//            xcw.name(eName.getLocalPart())
//               .namespace(eName.getNamespaceURI());
//        }

        if (types.size() == 1) {
            CTypeRef t = types.get(0);
            writeXmlElementAnnotation(field, t, resolve(t,IMPLEMENTATION), false);
        } else {
            for (CTypeRef t : types) {
                // generate @XmlElements
                writeXmlElementAnnotation(field, t, resolve(t,IMPLEMENTATION), true);
            }
            xesw = null;
        }
    }

    /**
     * Generate the simplest XmlElement annotation possible taking all semantic optimizations
     * into account.  This method is essentially equivalent to:
     *
     *     xew.name(ctype.getTagName().getLocalPart())
     *        .namespace(ctype.getTagName().getNamespaceURI())
     *        .type(jtype)
     *        .defaultValue(ctype.getDefaultValue());
     *
     * @param field
     * @param ctype
     * @param jtype
     * @param checkWrapper true if the method might need to generate XmlElements
     */
    private void writeXmlElementAnnotation( JAnnotatable field, CTypeRef ctype, JType jtype,
                                            boolean checkWrapper ) {

        // lazily create - we don't know if we need to generate anything yet
        XmlElementWriter xew = null;

        // these values are used to determine how to optimize the generated annotation
        XmlNsForm formDefault = parent()._package().getElementFormDefault();
        String propName = prop.getName(false);

        String enclosingTypeNS;

        if(parent().target.getTypeName()==null)
            enclosingTypeNS = parent()._package().getMostUsedNamespaceURI();
        else
            enclosingTypeNS = parent().target.getTypeName().getNamespaceURI();

        // generate the name property?
        String generatedName = ctype.getTagName().getLocalPart();
        if(!generatedName.equals(propName)) {
            if(xew == null) xew = getXew(checkWrapper, field);
            xew.name(generatedName);
        }

        // generate the namespace property?
        String generatedNS = ctype.getTagName().getNamespaceURI();
        if (((formDefault == XmlNsForm.QUALIFIED) && !generatedNS.equals(enclosingTypeNS)) ||
                ((formDefault == XmlNsForm.UNQUALIFIED) && !generatedNS.equals(""))) {
            if(xew == null) xew = getXew(checkWrapper, field);
            xew.namespace(generatedNS);
        }

        // generate the required() property?
        CElementPropertyInfo ep = (CElementPropertyInfo) prop;
        if(ep.isRequired() && exposedType.isReference()) {
            if(xew == null) xew = getXew(checkWrapper, field);
            xew.required(true);
        }

        // generate the type property?

        // I'm not too sure if this is the right place to handle this, but
        // if the schema definition is requiring this element, we should point to a primitive type,
        // not wrapper type (to correctly carry forward the required semantics.)
        // if it's a collection, we can't use a primitive, however.
        if(ep.isRequired() && !prop.isCollection())
            jtype = jtype.unboxify();

        // when generating code for 1.4, the runtime can't infer that ArrayList<Foo> derives
        // from Collection<Foo> (because List isn't parameterized), so always expclitly
        // generate @XmlElement(type=...)
        if( !jtype.equals(exposedType) || (getOptions().runtime14 && prop.isCollection())) {
            if(xew == null) xew = getXew(checkWrapper, field);
            xew.type(jtype);
        }

        // generate defaultValue property?
        final String defaultValue = ctype.getDefaultValue();
        if (defaultValue!=null) {
            if(xew == null) xew = getXew(checkWrapper, field);
            xew.defaultValue(defaultValue);
        }

        // generate the nillable property?
        if (ctype.isNillable()) {
            if(xew == null) xew = getXew(checkWrapper, field);
            xew.nillable(true);
        }
    }

    /**
     * Gets the {@link Options} in the current compilation context.
     */
    protected final Options getOptions() {
        return parent().parent().getModel().options;
    }

    // ugly hack to lazily create
    private XmlElementsWriter xesw = null;

    private XmlElementWriter getXew(boolean checkWrapper, JAnnotatable field) {
        XmlElementWriter xew;
        if(checkWrapper) {
            if(xesw==null) {
                xesw = field.annotate2(XmlElementsWriter.class);
            }
            xew = xesw.value();
        } else {
            xew = field.annotate2(XmlElementWriter.class);
        }
        return xew;
    }

    /**
     * Annotate the attribute property 'field'
     */
    private void annotateAttribute(JAnnotatable field) {
        CAttributePropertyInfo ap = (CAttributePropertyInfo) prop;
        QName attName = ap.getXmlName();

        // [RESULT]
        // @XmlAttribute(name="foo", required=true, namespace="bar://baz")
        XmlAttributeWriter xaw = field.annotate2(XmlAttributeWriter.class);

        final String generatedName = attName.getLocalPart();
        final String generatedNS = attName.getNamespaceURI();

        // Issue 570; always force generating name="" when do it when globalBindings underscoreBinding is set to non default value
        // generate name property?
        if(!generatedName.equals(ap.getName(false)) || !generatedName.equals(ap.getName(true)) || (outline.parent().getModel().getNameConverter() != NameConverter.standard)) {
            xaw.name(generatedName);
        }

        // generate namespace property?
        if(!generatedNS.equals("")) { // assume attributeFormDefault == unqualified
            xaw.namespace(generatedNS);
        }

        // generate required property?
        if(ap.isRequired()) {
            xaw.required(true);
        }
    }

    /**
     * Useful base class for implementing {@link FieldAccessor}.
     */
    protected abstract class Accessor implements FieldAccessor {

        /**
         * Evaluates to the target object this accessor should access.
         */
        protected final JExpression $target;
        
        protected Accessor( JExpression $target ) {
            this.$target = $target;
        }

        public final FieldOutline owner() {
            return AbstractField.this;
        }

        public final CPropertyInfo getPropertyInfo() {
            return prop;
        }
    }
    
    
//
//
//     utility methods
//
//

    /**
     * Generates the field declaration.
     */
    protected final JFieldVar generateField( JType type ) {
        return outline.implClass.field( JMod.PROTECTED, type, prop.getName(false) );
    }

    /**
     * Case from {@link #exposedType} to {@link #implType} if necessary.
     */
    protected final JExpression castToImplType( JExpression exp ) {
        if(implType==exposedType)
            return exp;
        else
            return JExpr.cast(implType,exp);
    }

    /**
     * Compute the type of a {@link CPropertyInfo}
     * @param aspect
     */
    protected JType getType(final Aspect aspect) {
        if(prop.getAdapter()!=null)
            return prop.getAdapter().customType.toType(outline.parent(),aspect);

        final class TypeList extends ArrayList<JType> {
            void add( CTypeInfo t ) {
                add( t.getType().toType(outline.parent(),aspect) );
                if(t instanceof CElementInfo) {
                    // UGLY. element substitution is implemented in a way that
                    // the derived elements are not assignable to base elements.
                    // so when we compute the signature, we have to take derived types
                    // into account
                    add( ((CElementInfo)t).getSubstitutionMembers());
                }
            }

            void add( Collection<? extends CTypeInfo> col ) {
                for (CTypeInfo typeInfo : col)
                    add(typeInfo);
            }
        }
        TypeList r = new TypeList();
        r.add(prop.ref());

        JType t;
        if(prop.baseType!=null)
            t = prop.baseType;
        else
            t = TypeUtil.getCommonBaseType(codeModel,r);

        // if item type is unboxable, convert t=Integer -> t=int
        // the in-memory data structure can't have primitives directly,
        // but this guarantees that items cannot legal hold null,
        // which helps us improve the boundary signature between our
        // data structure and user code
        if(prop.isUnboxable())
            t = t.unboxify();
        return t;
    }

    /**
     * Returns contents to be added to javadoc.
     */
    protected final List<Object> listPossibleTypes( CPropertyInfo prop ) {
        List<Object> r = new ArrayList<Object>();
        List<JType> refs = prop.ref().stream()
                .map(tt -> tt.toType(outline.parent(), Aspect.EXPOSED))
                .sorted(comparing(JType::fullName))
                .collect(toList());
        for( JType t : refs ) {
            if( t.isPrimitive() || t.isArray() )
                r.add(t.fullName());
            else {
                r.add(t);
                r.add("\n");
            }
        }

        return r;
    }

    /**
     * return the Java type for the given type reference in the model.
     */
    private JType resolve(CTypeRef typeRef,Aspect a) {
        return outline.parent().resolve(typeRef,a);
    }

}
