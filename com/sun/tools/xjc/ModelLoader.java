/*
 * Copyright (c) 1997, 2021 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package com.sun.tools.xjc;

import java.io.IOException;
import java.io.StringReader;

import com.sun.codemodel.JCodeModel;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.reader.Const;
import com.sun.tools.xjc.reader.ExtensionBindingChecker;
import com.sun.tools.xjc.reader.dtd.TDTDReader;
import com.sun.tools.xjc.reader.internalizer.DOMForest;
import com.sun.tools.xjc.reader.internalizer.DOMForestScanner;
import com.sun.tools.xjc.reader.internalizer.InternalizationLogic;
import com.sun.tools.xjc.reader.internalizer.SCDBasedBindingSet;
import com.sun.tools.xjc.reader.internalizer.VersionChecker;
import com.sun.tools.xjc.reader.xmlschema.BGMBuilder;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.AnnotationParserFactoryImpl;
import com.sun.tools.xjc.reader.xmlschema.parser.CustomizationContextChecker;
import com.sun.tools.xjc.reader.xmlschema.parser.IncorrectNamespaceURIChecker;
import com.sun.tools.xjc.reader.xmlschema.parser.SchemaConstraintChecker;
import com.sun.tools.xjc.reader.xmlschema.parser.XMLSchemaInternalizationLogic;
import com.sun.tools.xjc.util.ErrorReceiverFilter;
import com.sun.xml.bind.v2.util.XmlFactory;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.parser.JAXPParser;
import com.sun.xml.xsom.parser.XMLParser;
import com.sun.xml.xsom.parser.XSOMParser;
import javax.xml.XMLConstants;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * Builds a {@link Model} object.
 *
 * This is an utility class that makes it easy to load a grammar object
 * from various sources.
 *
 * @author
 *     Kohsuke Kawaguchi (kohsuke.kawaguchi@sun.com)
 */
public final class ModelLoader {

    private final Options opt;
    private final ErrorReceiverFilter errorReceiver;
    private final JCodeModel codeModel;
    /**
     * {@link DOMForest#transform(boolean)} creates this on the side.
     */
    private SCDBasedBindingSet scdBasedBindingSet;


    /**
     * A convenience method to load schemas into a {@link Model}.
     */
    public static Model load( Options opt, JCodeModel codeModel, ErrorReceiver er ) {
        return new ModelLoader(opt,codeModel,er).load();
    }


    public ModelLoader(Options _opt, JCodeModel _codeModel, ErrorReceiver er) {
        this.opt = _opt;
        this.codeModel = _codeModel;
        this.errorReceiver = new ErrorReceiverFilter(er);
    }

    @SuppressWarnings("CallToThreadDumpStack")
    private Model load() {
        Model grammar;

        if(!sanityCheck())
            return null;


        try {
            switch (opt.getSchemaLanguage()) {
            case DTD :
                // TODO: make sure that bindFiles,size()<=1
                InputSource bindFile = null;
                if (opt.getBindFiles().length > 0)
                    bindFile = opt.getBindFiles()[0];
                // if there is no binding file, make a dummy one.
                if (bindFile == null) {
                    // if no binding information is specified, provide a default
                    bindFile =
                        new InputSource(
                            new StringReader(
                                "<?xml version='1.0'?><xml-java-binding-schema><options package='"
                                    + (opt.defaultPackage==null?"generated":opt.defaultPackage)
                                    + "'/></xml-java-binding-schema>"));
                }

                checkTooManySchemaErrors();
                grammar = loadDTD(opt.getGrammars()[0], bindFile );
                break;

            case WSDL:
                grammar = annotateXMLSchema( loadWSDL() );
                break;

            case XMLSCHEMA:
                grammar = annotateXMLSchema( loadXMLSchema() );
                break;

            default :
                throw new AssertionError(); // assertion failed
            }

            if (errorReceiver.hadError()) {
                grammar = null;
            } else {
                grammar.setPackageLevelAnnotations(opt.packageLevelAnnotations);
            }

            return grammar;

        } catch (SAXException e) {
            // parsing error in the input document.
            // this error must have been reported to the user vis error handler
            // so don't print it again.
            if (opt.verbose) {
                // however, a bug in XJC might throw unexpected SAXException.
                // thus when one is debugging, it is useful to print what went
                // wrong.
                if (e.getException() != null)
                    e.getException().printStackTrace();
                else
                    e.printStackTrace();
            }
            return null;
        } catch (AbortException e) {
            // error should have been reported already, since this is requested by the error receiver
            return null;
        }
    }



    /**
     * Do some extra checking and return false if the compilation
     * should abort.
     */
    private boolean sanityCheck() {
        if( opt.getSchemaLanguage()!=Language.XMLSCHEMA ) {
            Language guess = opt.guessSchemaLanguage();

            String[] msg = null;
            switch(guess) {
            case DTD:
                msg = new String[]{"DTD","-dtd"};
                break;
            case WSDL:
                msg = new String[]{"WSDL","-wsdl"};
                break;
            }
            if( msg!=null )
                errorReceiver.warning( null,
                    Messages.format(
                    Messages.EXPERIMENTAL_LANGUAGE_WARNING,
                    msg[0], msg[1] ));
        }
        return true;
    }


    /**
     * {@link XMLParser} implementation that adds additional processors into the chain.
     *
     * <p>
     * This parser will parse a DOM forest as:
     * DOMForestParser -->
     *   ExtensionBindingChecker -->
     *     ProhibitedFeatureFilter -->
     *       XSOMParser
     */
    private class XMLSchemaParser implements XMLParser {
        private final XMLParser baseParser;

        private XMLSchemaParser(XMLParser baseParser) {
            this.baseParser = baseParser;
        }

        public void parse(InputSource source, ContentHandler handler,
            ErrorHandler errorHandler, EntityResolver entityResolver ) throws SAXException, IOException {
            // set up the chain of handlers.
            handler = wrapBy( new ExtensionBindingChecker(XMLConstants.W3C_XML_SCHEMA_NS_URI,opt,errorReceiver), handler );
            handler = wrapBy( new IncorrectNamespaceURIChecker(errorReceiver), handler );
            handler = wrapBy( new CustomizationContextChecker(errorReceiver), handler );
//          handler = wrapBy( new VersionChecker(controller), handler );

            baseParser.parse( source, handler, errorHandler, entityResolver );
        }
        /**
         * Wraps the specified content handler by a filter.
         * It is little awkward to use a helper implementation class like XMLFilterImpl
         * as the method parameter, but this simplifies the code.
         */
        private ContentHandler wrapBy( XMLFilterImpl filter, ContentHandler handler ) {
            filter.setContentHandler(handler);
            return filter;
        }
    }

    private void checkTooManySchemaErrors() {
        if( opt.getGrammars().length!=1 )
            errorReceiver.error(null,Messages.format(Messages.ERR_TOO_MANY_SCHEMA));
    }

    /**
     * Parses a DTD file into an annotated grammar.
     *
     * @param   source
     *      DTD file
     * @param   bindFile
     *      External binding file.
     */
    private Model loadDTD( InputSource source, InputSource bindFile) {

        // parse the schema as a DTD.
        return TDTDReader.parse(
            source,
            bindFile,
            errorReceiver,
            opt);
    }

    /**
     * Builds DOMForest and performs the internalization.
     *
     * @throws SAXException
     *      when a fatal error happens
     */
    public DOMForest buildDOMForest( InternalizationLogic logic )
        throws SAXException {

        // parse into DOM forest
        DOMForest forest = new DOMForest(logic, opt);

        forest.setErrorHandler(errorReceiver);
        if(opt.entityResolver!=null)
        forest.setEntityResolver(opt.entityResolver);

        // parse source grammars
        for (InputSource value : opt.getGrammars()) {
            errorReceiver.pollAbort();
            forest.parse(value, true);
        }

        // parse external binding files
        for (InputSource value : opt.getBindFiles()) {
            errorReceiver.pollAbort();
            Document dom = forest.parse(value, true);
            if(dom==null)       continue;   // error must have been reported
            Element root = dom.getDocumentElement();
            // TODO: it somehow doesn't feel right to do a validation in the Driver class.
            // think about moving it to somewhere else.
            if (!fixNull(root.getNamespaceURI()).equals(Const.JAXB_NSURI)
                    || !root.getLocalName().equals("bindings"))
                errorReceiver.error(new SAXParseException(Messages.format(Messages.ERR_NOT_A_BINDING_FILE,
                        root.getNamespaceURI(),
                        root.getLocalName()),
                        null,
                        value.getSystemId(),
                        -1, -1));
        }

        scdBasedBindingSet = forest.transform(opt.isExtensionMode());

        return forest;
    }

    private String fixNull(String s) {
        if(s==null) return "";
        else        return s;
    }

    /**
     * Parses a set of XML Schema files into an annotated grammar.
     */
    public XSSchemaSet loadXMLSchema() throws SAXException {

        if( opt.strictCheck && !SchemaConstraintChecker.check(opt.getGrammars(),errorReceiver,opt.entityResolver, opt.disableXmlSecurity)) {
            // schema error. error should have been reported
            return null;
        }

        if(opt.getBindFiles().length==0) {
            // no external binding. try the speculative no DOMForest execution,
            // which is faster if the speculation succeeds.
            try {
                return createXSOMSpeculative();
            } catch( SpeculationFailure e) {
                // failed. go the slow way
            }
        }

        // the default slower way is to parse everything into DOM first.
        // so that we can take external annotations into account.
        DOMForest forest = buildDOMForest( new XMLSchemaInternalizationLogic() );
        return createXSOM(forest, scdBasedBindingSet);
    }

    /**
     * Parses a set of schemas inside a WSDL file.
     *
     * A WSDL file may contain multiple {@code <xsd:schema>} elements.
     */
    private XSSchemaSet loadWSDL()
        throws SAXException {


        // build DOMForest just like we handle XML Schema
        DOMForest forest = buildDOMForest( new XMLSchemaInternalizationLogic() );

        DOMForestScanner scanner = new DOMForestScanner(forest);

        XSOMParser xsomParser = createXSOMParser( forest );

        // find <xsd:schema>s and parse them individually
        for( InputSource grammar : opt.getGrammars() ) {
            Document wsdlDom = forest.get( grammar.getSystemId() );
            if (wsdlDom == null) {
                String systemId = Options.normalizeSystemId(grammar.getSystemId());
                if (forest.get(systemId) != null) {
                    grammar.setSystemId(systemId);
                    wsdlDom = forest.get( grammar.getSystemId() );
                }
            }

            NodeList schemas = wsdlDom.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI,"schema");
            for( int i=0; i<schemas.getLength(); i++ )
                scanner.scan( (Element)schemas.item(i), xsomParser.getParserHandler() );
        }
        return xsomParser.getResult();
    }

    /**
     * Annotates the obtained schema set.
     *
     * @return
     *      null if an error happens. In that case, the error messages
     *      will be properly reported to the controller by this method.
     */
    public Model annotateXMLSchema(XSSchemaSet xs) {
        if (xs == null)
            return null;
        return BGMBuilder.build(xs, codeModel, errorReceiver, opt);
    }

    /**
     * Potentially problematic - make sure the parser instance passed is initialized
     * with proper security feature.
     *
     * @param parser
     * @return
     */
    public XSOMParser createXSOMParser(XMLParser parser) {
        // set up other parameters to XSOMParser
        XSOMParser reader = new XSOMParser(new XMLSchemaParser(parser));
        reader.setAnnotationParser(new AnnotationParserFactoryImpl(opt));
        reader.setErrorHandler(errorReceiver);
        reader.setEntityResolver(opt.entityResolver);
        return reader;
    }

    public XSOMParser createXSOMParser(final DOMForest forest) {
        XSOMParser p = createXSOMParser(forest.createParser());
        p.setEntityResolver(new EntityResolver() {
            public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
                // DOMForest only parses documents that are reachable through systemIds,
                // and it won't pick up references like <xs:import namespace="..." /> without
                // @schemaLocation. So we still need to use an entity resolver here to resolve
                // these references, yet we don't want to just run them blindly, since if we do that
                // DOMForestParser always get the translated system ID when catalog is used
                // (where DOMForest records trees with their original system IDs.)
                if(systemId!=null && forest.get(systemId)!=null)
                    return new InputSource(systemId);
                if(opt.entityResolver!=null)
                    return opt.entityResolver.resolveEntity(publicId,systemId);

                return null;
            }
        });
        return p;
    }


    private static final class SpeculationFailure extends Error {}

    private static final class SpeculationChecker extends XMLFilterImpl {
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if(localName.equals("bindings") && uri.equals(Const.JAXB_NSURI))
                throw new SpeculationFailure();
            super.startElement(uri,localName,qName,attributes);
        }
    }

    /**
     * Parses schemas directly into XSOM by assuming that there's
     * no external annotations.
     * <p>
     * When an external annotation is found, a {@link SpeculationFailure} is thrown,
     * and we will do it all over again by using the slow way.
     */
    private XSSchemaSet createXSOMSpeculative() throws SAXException, SpeculationFailure {

        // check if the schema contains external binding files. If so, speculation is a failure.

        XMLParser parser = new XMLParser() {
            private final JAXPParser base = new JAXPParser(XmlFactory.createParserFactory(opt.disableXmlSecurity));

            public void parse(InputSource source, ContentHandler handler,
                ErrorHandler errorHandler, EntityResolver entityResolver ) throws SAXException, IOException {
                // set up the chain of handlers.
                handler = wrapBy( new SpeculationChecker(), handler );
                handler = wrapBy( new VersionChecker(null,errorReceiver,entityResolver), handler );

                base.parse( source, handler, errorHandler, entityResolver );
            }
            /**
             * Wraps the specified content handler by a filter.
             * It is little awkward to use a helper implementation class like XMLFilterImpl
             * as the method parameter, but this simplifies the code.
             */
            private ContentHandler wrapBy( XMLFilterImpl filter, ContentHandler handler ) {
                filter.setContentHandler(handler);
                return filter;
            }
        };

        XSOMParser reader = createXSOMParser(parser);

        // parse source grammars
        for (InputSource value : opt.getGrammars())
            reader.parse(value);

        return reader.getResult();
    }

    /**
     * Parses a {@link DOMForest} into a {@link XSSchemaSet}.
     *
     * @return
     *      null if the parsing failed.
     */
    public XSSchemaSet createXSOM(DOMForest forest, SCDBasedBindingSet scdBasedBindingSet) throws SAXException {
        // set up other parameters to XSOMParser
        XSOMParser reader = createXSOMParser(forest);

        // re-parse the transformed schemas
        for (String systemId : forest.getRootDocuments()) {
            errorReceiver.pollAbort();
            Document dom = forest.get(systemId);
            if (!dom.getDocumentElement().getNamespaceURI().equals(Const.JAXB_NSURI)) {
                reader.parse(systemId);
            }
        }

        XSSchemaSet result = reader.getResult();

        if(result!=null)
            scdBasedBindingSet.apply(result,errorReceiver);

        return result;
    }

}
