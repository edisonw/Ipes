package com.edisonwang.eventservice.processors;

import com.edisonwang.eventservice.annotations.RequestFactory;
import com.edisonwang.eventservice.annotations.ClassField;
import com.edisonwang.eventservice.annotations.RequestFactoryWithClass;
import com.edisonwang.eventservice.annotations.RequestFactoryWithVariables;
import com.google.auto.service.AutoService;
import com.google.common.base.Joiner;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Annotated Class -> Groups of classes.
 *
 * @author edi
 */
@AutoService(Processor.class)
public class RequestFactoryGenerator extends AbstractProcessor {

    private static final Set<String> NAMES;

    static {
        HashSet<String> set = new HashSet<>(1);
        set.add(RequestFactory.class.getCanonicalName());
        set.add(RequestFactoryWithClass.class.getCanonicalName());
        set.add(RequestFactoryWithVariables.class.getCanonicalName());
        set.add(ClassField.class.getCanonicalName());
        NAMES = Collections.unmodifiableSet(set);
    }

    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return NAMES;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<String, TypeSpec.Builder> builderMap = new LinkedHashMap<>();
        Map<String, HashSet<String>> groupToPackage = new HashMap<>();
        // Iterate over all @Factory annotated elements
        System.out.print("Processing aggregations...\n");
        for (Element element : roundEnv.getElementsAnnotatedWith(RequestFactory.class)) {
            // Check if a class has been annotated with @Factory
            if (element.getKind() != ElementKind.CLASS) {
                error(element, "You cannot annotate " + element.getSimpleName() + " with " + RequestFactory.class);
                return true;
            }
            TypeElement classElement = (TypeElement) element;
            RequestFactory annotationElement = classElement.getAnnotation(RequestFactory.class);

            //Groups of Objects, named.

            String group = annotationElement.group();
            if (group == null || group.length() == 0) {
                throw new IllegalArgumentException(
                        String.format("group() in @%s for class %s is null or empty! that's not allowed",
                                RequestFactory.class.getSimpleName(), classElement.getQualifiedName().toString()));
            }

            TypeMirror baseTypeMirror = null;
            try {
                annotationElement.baseClass();
            } catch (MirroredTypeException mte) {
                baseTypeMirror = mte.getTypeMirror();
            }

            if (baseTypeMirror == null) {
                throw new IllegalArgumentException(
                        String.format("valueType() in @%s for class %s is null or empty! that's not allowed",
                                RequestFactory.class.getSimpleName(), classElement.getQualifiedName().toString()));
            }


            TypeMirror valueTypeMirror = null;

            try {
                annotationElement.valueType();
            } catch (MirroredTypeException mte) {
                valueTypeMirror = mte.getTypeMirror();
            }

            if (valueTypeMirror == null) {
                throw new IllegalArgumentException(
                        String.format("valueType() in @%s for class %s is null or empty! that's not allowed",
                                RequestFactory.class.getSimpleName(), classElement.getQualifiedName().toString()));
            }

            TypeName baseClassType = TypeName.get(baseTypeMirror);
            TypeName valueClassType = TypeName.get(valueTypeMirror);

            if (valueClassType.toString().equals(classElement.getQualifiedName().toString())) {
                System.out.print("Ignoring base type...." + classElement.getQualifiedName() + "\n");
                continue;
            }

            System.out.print("Got base class..." + baseTypeMirror + "\n");

            String baseClass = baseTypeMirror.toString();

            String groupId = baseClass + "_." + group;

            HashSet<String> packageGroups = groupToPackage.get(baseTypeMirror.toString());

            if (packageGroups == null) {
                packageGroups = new HashSet<>();
                groupToPackage.put(baseClass, packageGroups);
            }

            packageGroups.add(groupId);

            String enumName = classElement.getSimpleName().toString();

            TypeSpec.Builder groupSpec = builderMap.get(groupId);
            if (groupSpec == null) {
                groupSpec = TypeSpec.enumBuilder(group);
                groupSpec.addModifiers(Modifier.PUBLIC);
                groupSpec.addSuperinterface(baseClassType);
                groupSpec.addField(valueClassType,
                        "value", Modifier.PRIVATE, Modifier.FINAL)
                        .addMethod(MethodSpec.constructorBuilder()
                                .addParameter(valueClassType, "value")
                                .addStatement("this.$N = $N", "value", "value")
                                .build());
                groupSpec.addMethod(MethodSpec.methodBuilder("value").addModifiers(Modifier.PUBLIC)
                        .returns(valueClassType).addStatement("return this.value").build());
                builderMap.put(groupId, groupSpec);
            }

            groupSpec.addEnumConstant(enumName,
                    TypeSpec.anonymousClassBuilder("new $L()", classElement) //Empty Constructor required.
                            .build());

            RequestFactoryWithClass factoryAnnotation = classElement.getAnnotation(RequestFactoryWithClass.class);
            if (factoryAnnotation != null) {
                addFactoryMethodToGroupSpec(factoryAnnotation, classElement, enumName, groupSpec);
            }

            RequestFactoryWithVariables variables = classElement.getAnnotation(RequestFactoryWithVariables.class);

            if (variables != null) {
                addFactoryAndFactoryMethod(variables, classElement, enumName, groupSpec, groupId);
            }

        }

        for (String baseClass : groupToPackage.keySet()) {
            /**
             * public enum Classs {
             * Action_B(new Integer(1)),
             * Action_A(new String(""));
             * <p/>
             * private Object action;
             * <p/>
             * Classs(Object clazz) {
             * action = clazz;
             * }
             * }
             */
            HashSet<String> groups = groupToPackage.get(baseClass);
            for (String groupId : groups) {
                TypeSpec typeSpec = builderMap.get(groupId).build();
                System.out.print("Generating class " + groupId + "\n");
                try {
                    Writer writer = filer.createSourceFile(groupId).openWriter();
                    JavaFile jf = JavaFile.builder(groupId.substring(0, groupId.lastIndexOf(".")), typeSpec).build();
                    jf.writeTo(writer);
                    writer.close();
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to write class.", e);
                }
            }
        }

        return true;
    }

    private void addFactoryAndFactoryMethod(RequestFactoryWithVariables anno, TypeElement classElement,
                                            String enumName, TypeSpec.Builder groupSpec, String groupId) {

        TypeMirror baseTypeMirror = null;
        try {
            anno.baseClass();
        } catch (MirroredTypeException mte) {
            baseTypeMirror = mte.getTypeMirror();
        }

        if (baseTypeMirror == null) {
            throw new IllegalArgumentException(
                    String.format("baseClass() in @%s for class %s is null or empty! that's not allowed",
                            RequestFactoryWithVariables.class.getSimpleName(), classElement.getQualifiedName().toString()));
        }

        //Only primitives are supported.
        ClassField[] variables = anno.variables();

        String packageName = classElement.getQualifiedName().toString().substring(0, classElement.getQualifiedName().toString().lastIndexOf("."));
        String className = enumName + "Helper";
        String qualifiedName = packageName + "." + className;

        try {
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(qualifiedName);
            Writer writer = sourceFile.openWriter();
            //Custom code for us.
            writer.write(Joiner.on('\n').join(
                    "package " + packageName + ";",
                    "public class " + className + " extends " + baseTypeMirror.toString() + " {",
                    "{ mTarget=" + groupId + "." + enumName + "; }"
            ));
            for (ClassField variable : variables) {
                String name = variable.name();
                String kindName;
                try {
                    Class k = variable.kind();
                    kindName = k.getName();
                } catch (MirroredTypeException mte) {
                    kindName = mte.getTypeMirror().toString();
                }
                writer.write("\npublic " + className + " " + name + "(" + kindName + " value){\n mVariableHolder.putExtra(\"" +name + "\", value);\nreturn this; \n}");
                writer.write("\npublic " + kindName + " " + name+ "() {\n return (" + kindName + ") get(\""+ name +"\");\n }\n");
            }
            writer.write("}\n");
            writer.close();

            char c[] = enumName.toCharArray();
            c[0] = Character.toLowerCase(c[0]);
            String methodName = new String(c);

            groupSpec.addMethod(MethodSpec.methodBuilder(methodName)
                    .addModifiers(Modifier.FINAL, Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ClassName.bestGuess(qualifiedName)).addStatement(
                            "return new " + qualifiedName + "()").build());
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to write.", e);
        }
    }

    private void addFactoryMethodToGroupSpec(RequestFactoryWithClass factoryAnnotation,
                                             TypeElement classElement, String enumName,
                                             TypeSpec.Builder groupSpec) {
        TypeMirror factoryClassMirror = null;
        try {
            factoryAnnotation.factoryClass();
        } catch (MirroredTypeException mte) {
            factoryClassMirror = mte.getTypeMirror();
        }

        if (factoryClassMirror == null) {
            throw new IllegalArgumentException(
                    String.format("factoryClass() in @%s for class %s is null or empty! that's not allowed",
                            RequestFactoryWithClass.class.getSimpleName(),
                            classElement.getQualifiedName().toString()));
        }

        char c[] = enumName.toCharArray();
        c[0] = Character.toLowerCase(c[0]);
        String methodName = new String(c);

        groupSpec.addMethod(MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.FINAL, Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.get(factoryClassMirror)).addStatement(
                        "return new " + factoryClassMirror.toString() + "()").build());
    }

    private void error(Element e, String msg, Object... args) {
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                String.format(msg, args),
                e);
    }

}
