package com.edisonwang.ps.processors;

import com.edisonwang.ps.annotations.Action;
import com.edisonwang.ps.annotations.ActionHelper;
import com.edisonwang.ps.annotations.ActionHelperFactory;
import com.edisonwang.ps.annotations.Default;
import com.edisonwang.ps.annotations.Event;
import com.edisonwang.ps.annotations.EventListener;
import com.edisonwang.ps.annotations.EventProducer;
import com.edisonwang.ps.annotations.Field;
import com.edisonwang.ps.annotations.Kind;
import com.edisonwang.ps.annotations.ParcelableField;
import com.google.auto.service.AutoService;
import com.google.common.base.Joiner;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;

import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.IncompleteAnnotationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

/**
 * @author edi
 */
@AutoService(Processor.class)
public class PennStationProcessor extends AbstractProcessor {

    private static final Set<String> NAMES;

    static {
        HashSet<String> set = new HashSet<>();
        set.add(Action.class.getCanonicalName());
        set.add(ActionHelperFactory.class.getCanonicalName());
        set.add(ActionHelper.class.getCanonicalName());
        set.add(EventListener.class.getCanonicalName());
        set.add(EventProducer.class.getCanonicalName());
        set.add(Field.class.getCanonicalName());
        NAMES = Collections.unmodifiableSet(set);
    }

    private Filer filer;
    private Messager messager;
    private Elements elementUtils;
    private Class<?> rxFactoryClass;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        filer = processingEnv.getFiler();
        elementUtils = processingEnv.getElementUtils();
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
        if (annotations.isEmpty()) {
            return false;
        }
        System.out.println("Processing aggregations:");
        System.out.println(annotations + " \n");
        boolean r = processEventProducersAndListeners(roundEnv);
        boolean r2 = processRequestFactory(roundEnv);
        return r && r2;
    }

    private boolean processRequestFactory(RoundEnvironment roundEnv) {
        // Iterate over all @Factory annotated elements
        for (Element element : roundEnv.getElementsAnnotatedWith(Action.class)) {
            // Check if a class has been annotated with @Factory
            if (element.getKind() != ElementKind.CLASS) {
                error(element, "You cannot annotate " + element.getSimpleName() + " with " + Action.class);
                return true;
            }
            TypeElement classElement = (TypeElement) element;
            Action annotationElement = classElement.getAnnotation(Action.class);

            //Groups of Objects, named.
            String baseClassString;

            try {
                baseClassString = annotationElement.base().getCanonicalName();
            } catch (MirroredTypeException mte) {
                baseClassString = mte.getTypeMirror().toString();
            }

            if (Default.class.getCanonicalName().equals(baseClassString)) {
                baseClassString = "com.edisonwang.ps.lib.ActionKey";
            }

            if (baseClassString == null) {
                throw new IllegalArgumentException(
                        String.format("valueType() in @%s for class %s is null or empty! that's not allowed",
                                Action.class.getSimpleName(), classElement.getQualifiedName().toString()));
            }

            String valueClassString;

            try {
                valueClassString = annotationElement.valueType().getCanonicalName();
            } catch (MirroredTypeException mte) {
                valueClassString = mte.getTypeMirror().toString();
            }

            if (valueClassString == null) {
                throw new IllegalArgumentException(
                        String.format("valueType() in @%s for class %s is null or empty! that's not allowed",
                                Action.class.getSimpleName(), classElement.getQualifiedName().toString()));
            }

            if (Default.class.getCanonicalName().equals(valueClassString)) {
                valueClassString = "com.edisonwang.ps.lib.Action";
            }

            TypeName baseClassType = ClassName.bestGuess(baseClassString);
            TypeName valueClassType = ClassName.bestGuess(valueClassString);

            if (valueClassType.toString().equals(classElement.getQualifiedName().toString())) {
                continue;
            }

            String enumName = classElement.getSimpleName().toString();

            String enumClass = "Ps" + annotationElement.group() + enumName;

            String packageName = classElement.getQualifiedName() + "_.";

            TypeSpec.Builder groupSpec = TypeSpec.enumBuilder(enumClass);
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

            groupSpec.addEnumConstant(enumName,
                    TypeSpec.anonymousClassBuilder("new $L()", classElement) //Empty Constructor required.
                            .build());

            ActionHelperFactory factoryAnnotation = classElement.getAnnotation(ActionHelperFactory.class);
            if (factoryAnnotation != null) {
                addFactoryMethodToGroupSpec(factoryAnnotation, classElement, enumName, groupSpec);
            }

            ActionHelper variables = classElement.getAnnotation(ActionHelper.class);

            if (variables != null) {
                addFactoryAndFactoryMethod(variables, classElement, enumName, groupSpec, packageName + enumClass, baseClassString);
            }

            writeClass(packageName + enumClass, groupSpec.build(), filer);

        }

        return true;
    }

    private void addFactoryAndFactoryMethod(ActionHelper anno, TypeElement classElement,
                                            String enumName, TypeSpec.Builder groupSpec, String groupId, String keyReturnClass) {

        String baseClassString;

        try {
            baseClassString = anno.base().getCanonicalName();
        } catch (MirroredTypeException mte) {
            baseClassString = mte.getTypeMirror().toString();
        }

        if (Default.class.getCanonicalName().equals(baseClassString)) {
            baseClassString = "com.edisonwang.ps.lib.ActionRequestHelper";
        }

        if (baseClassString == null) {
            throw new IllegalArgumentException(
                    String.format("base() in @%s for class %s is null or empty! that's not allowed",
                            ActionHelper.class.getSimpleName(), classElement.getQualifiedName().toString()));
        }

        //Only primitives are supported.
        Field[] variables = anno.args();

        String packageName = classElement.getQualifiedName().toString().substring(0, classElement.getQualifiedName().toString().lastIndexOf("."));
        String className = enumName + "Helper";
        String qualifiedName = packageName + "." + className;

        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .superclass(ClassName.bestGuess(baseClassString));
        // protected abstract ActionKey getActionKey();
        typeBuilder.addMethod(MethodSpec.methodBuilder("getActionKey").returns(
                ClassName.bestGuess(keyReturnClass)
        ).addModifiers(Modifier.PUBLIC)
                .addStatement(
                        "return $L.$L", groupId, enumName
                ).build());

        MethodSpec.Builder ctr = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

        String methodName = "helper";

        if (variables.length != 0) {
            ParameterSpec valuesParam = ParameterSpec.builder(
                    ClassName.bestGuess("android.os.Bundle"), "values").build();
            typeBuilder.addMethod(
                    MethodSpec.constructorBuilder().
                            addModifiers(Modifier.PUBLIC).addParameter(valuesParam)
                            .addStatement("setVariableValues(values)")
                            .build());
            groupSpec.addMethod(MethodSpec.methodBuilder(methodName)
                    .addModifiers(Modifier.FINAL, Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter(valuesParam)
                    .returns(ClassName.bestGuess(qualifiedName)).
                            addStatement("return new " + qualifiedName + "(values)").build());
        }

        ArrayList<String> requiredNames = new ArrayList<>();

        MethodSpec.Builder factoryMethod = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.FINAL, Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.bestGuess(qualifiedName));

        for (Field variable : variables) {
            ParsedKind kind = parseKind(variable.kind());
            TypeName kindClassName = kind.type;
            String name = variable.name();
            if (variable.required()) {
                requiredNames.add(name);
                ctr.addParameter(ParameterSpec.builder(kindClassName, name).build());
                ctr.addStatement("mVariableHolder.putExtra(\"$L\", $L)", name, name);
                factoryMethod.addParameter(ParameterSpec.builder(kindClassName, name).build());
            }
            typeBuilder.addMethod(MethodSpec.methodBuilder(name).addStatement(
                    "Object r = get(\"" + name + "\")"
            ).returns(kindClassName).addStatement(
                    "return r == null ? null : (" + kind.name + ") r"
            ).addModifiers(Modifier.PUBLIC).build());
            typeBuilder.addMethod(MethodSpec.methodBuilder(name).addParameter(
                    kindClassName, "value"
            ).returns(ClassName.bestGuess(className)).addStatement(
                    "mVariableHolder.putExtra(\"" + name + "\", value)"
            ).addStatement(
                    "return this"
            ).addModifiers(Modifier.PUBLIC).build());
        }

        typeBuilder.addMethod(ctr.build());

        writeClass(packageName, className, typeBuilder.build(), filer);

        factoryMethod.addStatement("return new " + qualifiedName + "(" + Joiner.on(",").join(requiredNames) + ")");

        groupSpec.addMethod(factoryMethod.build());
    }

    private ParsedKind parseKind(Kind kind) {
        String kindName;
        String kindParam;
        try {
            kindName = kind.clazz().getCanonicalName();
        } catch (MirroredTypeException mte) {
            kindName = mte.getTypeMirror().toString();
        }
        try {
            kindParam = kind.parameter().getCanonicalName();
        } catch (MirroredTypeException mte) {
            kindParam = mte.getTypeMirror().toString();
        }
        String className;
        TypeName classType;
        String baseName;
        if (!kindParam.equals(Default.class.getCanonicalName())) {
            ClassName kindClassName = ClassName.bestGuess(kindName);
            TypeName kindParamName = guessTypeName(kindParam);
            classType = ParameterizedTypeName.get(kindClassName, kindParamName);
            className = kindName + "<" + kindParam + ">";
            baseName = kindName;
        } else {
            classType = guessTypeName(kindName);
            className = kindName;
            baseName = className;
        }
        return new ParsedKind(classType, className, baseName);
    }

    private void addFactoryMethodToGroupSpec(ActionHelperFactory factoryAnnotation,
                                             TypeElement classElement, String enumName,
                                             TypeSpec.Builder groupSpec) {
        TypeMirror factoryClassMirror = null;
        try {
            factoryAnnotation.factory();
        } catch (MirroredTypeException mte) {
            factoryClassMirror = mte.getTypeMirror();
        }

        if (factoryClassMirror == null) {
            throw new IllegalArgumentException(
                    String.format("factory() in @%s for class %s is null or empty! that's not allowed",
                            ActionHelperFactory.class.getSimpleName(),
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

    private boolean processEventProducersAndListeners(RoundEnvironment roundEnv) {
        HashMap<String, HashSet<String>> producerEvents = new HashMap<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(EventProducer.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                error(element, "You cannot annotate " + element.getSimpleName() + " with " + EventProducer.class);
                return true;
            }
            getEventsFromProducer(producerEvents, (TypeElement) element);
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(EventListener.class)) {
            TypeElement typed = (TypeElement) element;
            EventListener annotationElement = typed.getAnnotation(EventListener.class);
            HashSet<String> producers = getAnnotatedClassesVariable(typed, "producers", EventListener.class);
            HashSet<String> listenedToEvents = new HashSet<>();
            for (String producer : producers) {
                HashSet<String> events = producerEvents.get(producer);
                if (events == null) {
                    events = getEventsFromProducer(producerEvents, elementUtils.getTypeElement(producer));
                    if (events == null) {
                        error(element, "Producer " + producer + " not registered, have you annotated it? ");
                    }
                    return true;
                }
                listenedToEvents.addAll(events);
            }

            String listenerClassName = typed.getSimpleName().toString() + EventListener.class.getSimpleName();
            String originalClassName = typed.getQualifiedName().toString();
            String packageName = packageFromQualifiedName(originalClassName);

            TypeSpec.Builder typeBuilder = TypeSpec.interfaceBuilder(listenerClassName).addModifiers(Modifier.PUBLIC);
            for (String event : listenedToEvents) {
                typeBuilder.addMethod(MethodSpec.methodBuilder(
                        (annotationElement.restrictMainThread() ? "onEventMainThread" : "onEvent"))
                        .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter(guessTypeName(event), "event").build());
            }
            writeClass(packageName, listenerClassName, typeBuilder.build(), filer);
        }
        Set<String> allProducers = producerEvents.keySet();
        for (String producer : allProducers) {
            String packageName = packageFromQualifiedName(producer);
            String listenerClassName = producer.substring(producer.lastIndexOf(".") + 1) + "Listener";
            HashSet<String> events = getEventsFromProducer(producerEvents, elementUtils.getTypeElement(producer));
            if (events != null) {
                TypeSpec.Builder typeBuilder = TypeSpec.interfaceBuilder(listenerClassName).addModifiers(Modifier.PUBLIC);
                for (String event : events) {
                    typeBuilder.addMethod(MethodSpec.methodBuilder("onEvent").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter(guessTypeName(event), "event").build());
                }
                writeClass(packageName, listenerClassName, typeBuilder.build(), filer);
                try {
                    addRxRequestClassContent(producer, packageName, packageName + "." + listenerClassName, events);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }

        return false;
    }

    private void addRxRequestClassContent(String producer, String packageName, String listenerClassName, HashSet<String> events) {
        ClassName resultClass = ClassName.bestGuess("com.edisonwang.ps.lib.ActionResult");

        TypeSpec removeSubscriptionSubscription = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(ClassName.bestGuess("rx.android.MainThreadSubscription"))
                .addMethod(MethodSpec.methodBuilder("onUnsubscribe").addStatement(
                        "com.edisonwang.ps.lib.PennStation.unRegisterListener(listener)"
                ).addModifiers(Modifier.PROTECTED).build()).build();

        TypeSpec.Builder eventListenerClass = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(ClassName.bestGuess(listenerClassName));

        for (String event : events) {
            eventListenerClass
                    .addMethod(MethodSpec.methodBuilder("onEvent")
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter(guessTypeName(event), "event")
                            .addStatement("subscriber.onNext(($L) event)", resultClass)
                            .build());
        }
        ParameterSpec subscriber =
                ParameterSpec.builder(ParameterizedTypeName.get(ClassName.bestGuess("rx.Subscriber"), WildcardTypeName.supertypeOf(resultClass)), "subscriber")
                        .addModifiers(Modifier.FINAL)
                        .build();
        TypeSpec eventListener = eventListenerClass.build();

        TypeSpec.Builder observerbleOnSubscribe = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(ParameterizedTypeName.get(ClassName.bestGuess("Observable.OnSubscribe"), resultClass));
        observerbleOnSubscribe.addMethod(MethodSpec.methodBuilder("call").addModifiers(Modifier.PUBLIC).addParameter(subscriber)
                .addStatement("final " + listenerClassName + " listener = $L", eventListener)
                .addStatement("com.edisonwang.ps.lib.PennStation.registerListener(listener)")
                .addStatement("subscriber.add($L)", removeSubscriptionSubscription).build());

        final String observerClassName = producer.substring(producer.lastIndexOf(".") + 1) + "Observer";
        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(observerClassName).addModifiers(Modifier.PUBLIC);
        MethodSpec.Builder method = MethodSpec.methodBuilder("create").addModifiers(Modifier.PUBLIC, Modifier.STATIC);
        ClassName rxObserverble = ClassName.bestGuess("rx.Observable");
        method.returns(ParameterizedTypeName.get(rxObserverble, resultClass)).addStatement("return $L.create($L)", rxObserverble, observerbleOnSubscribe.build());
        typeBuilder.addMethod(method.build());

        writeClass(packageName, observerClassName, typeBuilder.build(), filer);
    }

    private HashSet<String> getEventsFromProducer(HashMap<String, HashSet<String>> producerEvents, TypeElement typed) {
        String typedName = typed.getQualifiedName().toString();
        HashSet<String> events = producerEvents.get(typedName);
        if (events == null) {
            events = getAnnotatedClassesVariable(typed, "events", EventProducer.class);

            EventProducer eventProducer = typed.getAnnotation(EventProducer.class);

            if (eventProducer == null) {
                return null;
            }

            for (Event resultEvent : eventProducer.generated()) {
                try {
                    events.add(generateResultClass(typed, resultEvent));
                } catch (IncompleteAnnotationException e) {
                    System.err.println("Incomplete annotation found for " + typed.getQualifiedName());
                    throw e;
                }
            }

            producerEvents.put(typed.getQualifiedName().toString(), events);
        }

        return events;
    }

    private String generateResultClass(TypeElement typed, Event resultEvent) {
        String baseClassString;
        try {
            baseClassString = resultEvent.base().getCanonicalName();
        } catch (MirroredTypeException mte) {
            baseClassString = mte.getTypeMirror().toString();
        }

        if (Default.class.getCanonicalName().equals(baseClassString)) {
            baseClassString = "com.edisonwang.ps.lib.ActionResult";
        }

        List<ParcelableClassFieldParsed> parsed = new ArrayList<>();

        ParcelableField[] fields = resultEvent.fields();
        for (ParcelableField field : fields) {
            String parcelerName;
            try {
                parcelerName = field.parceler().getCanonicalName();
            } catch (MirroredTypeException mte) {
                parcelerName = mte.getTypeMirror().toString();
            }

            if (parcelerName.equals(Default.class.getCanonicalName())) {
                parcelerName = "com.edisonwang.ps.lib.parcelers.DefaultParceler";
            }

            parsed.add(new ParcelableClassFieldParsed(field.name(), parseKind(field.kind()),
                    parcelerName, field.required()));
        }

        try {
            String postFix = resultEvent.postFix();
            if (postFix == null || postFix.length() == 0) {
                postFix = "Event";
            }
            String eventClassName = typed.getSimpleName().toString() + postFix;
            String originalClassName = typed.getQualifiedName().toString();
            String packageName = packageFromQualifiedName(originalClassName);
            TypeName self = guessTypeName(eventClassName);

            if (baseClassString == null) {
                throw new IllegalStateException("Base type not found.");
            }

            TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(eventClassName)
                    .addModifiers(Modifier.PUBLIC)
                    .superclass(guessTypeName(baseClassString));

            for (ParcelableClassFieldParsed p : parsed) {
                typeBuilder.addField(p.kind.type, p.name, Modifier.PUBLIC);
            }

            addRxEventClassContent(typeBuilder, eventClassName);

            MethodSpec.Builder ctr = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

            for (ParcelableClassFieldParsed p : parsed) {
                if (p.required) {
                    ctr.addParameter(p.kind.type, p.name);
                    ctr.addStatement("this.$L = $L", p.name, p.name);
                }
            }
            typeBuilder.addMethod(ctr.build());

            ctr = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
            ctr.addParameter(guessTypeName("android.os.Parcel"), "in");
            for (ParcelableClassFieldParsed p : parsed) {
                ctr.addStatement("\tthis." + p.name + " = (" + p.kind.name + ")" + p.parcelerName + ".readFromParcel(in, " + p.kind.base + ".class)");
            }
            typeBuilder.addMethod(ctr.build());
            typeBuilder.addMethod(MethodSpec.methodBuilder("describeContents")
                    .returns(int.class)
                    .addStatement("return 0")
                    .addModifiers(Modifier.PUBLIC).build());

            MethodSpec.Builder writeToParcel = MethodSpec.methodBuilder("writeToParcel")
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(guessTypeName("android.os.Parcel"), "dest")
                    .addParameter(TypeName.INT, "flags");
            for (ParcelableClassFieldParsed p : parsed) {
                writeToParcel.addStatement("$L.writeToParcel(this.$L, dest, flags)", p.parcelerName, p.name);
            }

            typeBuilder.addMethod(writeToParcel.build());

            typeBuilder.addMethod(MethodSpec.methodBuilder("isSuccess")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(boolean.class)
                    .addStatement("return $L", resultEvent.success()).build());

            ClassName creatorClassName = ClassName.bestGuess("android.os.Parcelable.Creator");

            TypeSpec creator = TypeSpec.anonymousClassBuilder("")
                    .addSuperinterface(ParameterizedTypeName.get(creatorClassName, self))
                    .addMethod(MethodSpec.methodBuilder("createFromParcel")
                            .addModifiers(Modifier.PUBLIC)
                            .returns(self)
                            .addParameter(guessTypeName("android.os.Parcel"), "in")
                            .addStatement("return new $L(in)", eventClassName)
                            .build())
                    .addMethod(MethodSpec.methodBuilder("newArray")
                            .addModifiers(Modifier.PUBLIC)
                            .returns(ArrayTypeName.of(self))
                            .addParameter(TypeName.INT, "size")
                            .addStatement("return new $L[size]", eventClassName)
                            .build()
                    ).build();

            typeBuilder.addField(FieldSpec.builder(ParameterizedTypeName.get(creatorClassName, self),
                    "CREATOR", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$L", creator).build());

            writeClass(packageName + ".events", eventClassName, typeBuilder.build(), filer);

            return packageName + ".events." + eventClassName;
        } catch (Throwable e) {
            throw new IllegalArgumentException("Failed to write.", e);
        }
    }

    private String packageFromQualifiedName(String originalClassName) {
        return originalClassName.substring(0, originalClassName.lastIndexOf("."));
    }

    private HashSet<String> getAnnotatedClassesVariable(TypeElement element, String name, Class clazz) {
        HashSet<String> classes = new HashSet<>();

        AnnotationMirror am = null;
        List<? extends AnnotationMirror> mirrors = element.getAnnotationMirrors();
        for (AnnotationMirror mirror : mirrors) {
            if (mirror.getAnnotationType().toString().equals(clazz.getCanonicalName())) {
                am = mirror;
                break;
            }
        }
        AnnotationValue annotationEventValue = null;

        if (am == null) {
            return classes;
        }

        Map<? extends ExecutableElement, ? extends AnnotationValue> v = am.getElementValues();

        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : v.entrySet()) {
            if (name.equals(entry.getKey().getSimpleName().toString())) {
                annotationEventValue = entry.getValue();
                break;
            }
        }

        if (annotationEventValue != null) {
            List eventClasses = (List) annotationEventValue.getValue();
            for (Object c : eventClasses) {
                String extraLongClassName = c.toString();
                String regularClassName = extraLongClassName.substring(0, extraLongClassName.length() - ".class".length());
                classes.add(regularClassName);
            }
        }
        return classes;
    }

    public static TypeName guessTypeName(String classNameString) {
        if (classNameString.endsWith("[]")) {
            TypeName typeName = guessTypeName(classNameString.substring(0, classNameString.length() - 2));
            return ArrayTypeName.of(typeName);
        }
        if (double.class.getName().equals(classNameString)) {
            return TypeName.DOUBLE;
        } else if (int.class.getName().equals(classNameString)) {
            return TypeName.INT;
        } else if (boolean.class.getName().equals(classNameString)) {
            return TypeName.BOOLEAN;
        } else if (float.class.getName().equals(classNameString)) {
            return TypeName.FLOAT;
        } else if (byte.class.getName().equals(classNameString)) {
            return TypeName.BYTE;
        } else if (char.class.getName().equals(classNameString)) {
            return TypeName.CHAR;
        } else if (long.class.getName().equals(classNameString)) {
            return TypeName.LONG;
        } else if (short.class.getName().equals(classNameString)) {
            return TypeName.SHORT;
        }
        return ClassName.bestGuess(classNameString);
    }

    private void addRxEventClassContent(TypeSpec.Builder typeBuilder, String eventClassName) {
        if (rxFactoryClass == null) {
            try {
                rxFactoryClass = Class.forName("com.edisonwang.ps.rxpennstation.PsRxFactory");
            } catch (Throwable e) {
                // no rx present
            }

        }
        if (rxFactoryClass != null) {
            typeBuilder.addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(rxFactoryClass), ClassName.bestGuess(eventClassName)),
                    "Rx", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $L<>($L.class)", rxFactoryClass.getName(), eventClassName).build());
        }
    }

    public static void writeClass(String path,
                                  TypeSpec typeSpec,
                                  Filer filer) {
        writeClass(
                path,
                filer,
                JavaFile.builder(path.substring(0, path.lastIndexOf(".")), typeSpec).build());
    }

    private static void writeClass(String path, Filer filer, JavaFile jf) {
        try {
            Writer writer = filer.createSourceFile(path).openWriter();
            jf.writeTo(writer);
            writer.close();
            System.out.println("Generated " + path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to generate class: " + path, e);
        }
    }

    public static void writeClass(String packageName,
                                  String className,
                                  TypeSpec typeSpec,
                                  Filer filer) {
        writeClass(
                packageName + "." + className,
                filer,
                JavaFile.builder(packageName, typeSpec).build());
    }

    private static class ParcelableClassFieldParsed {

        public final String name;
        public final ParsedKind kind;
        public final String parcelerName;
        public final boolean required;

        public ParcelableClassFieldParsed(String name, ParsedKind kind,
                                          String parcelerName, boolean required) {
            this.name = name;
            this.kind = kind;
            this.parcelerName = parcelerName;
            this.required = required;
        }
    }

}
