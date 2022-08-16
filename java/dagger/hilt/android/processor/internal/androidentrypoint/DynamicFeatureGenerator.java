package dagger.hilt.android.processor.internal.androidentrypoint;

import com.google.auto.common.MoreElements;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeMirror;

import dagger.hilt.android.processor.internal.AndroidClassNames;
import dagger.hilt.processor.internal.ClassNames;
import dagger.hilt.processor.internal.ComponentNames;
import dagger.hilt.processor.internal.Processors;

public class DynamicFeatureGenerator {
    private final ProcessingEnvironment env;
    private final AndroidEntryPointMetadata metadata;
    private final ClassName wrapperClassName;
    private final ComponentNames componentNames;

    public DynamicFeatureGenerator(ProcessingEnvironment env, AndroidEntryPointMetadata metadata) {
        this.env = env;
        this.metadata = metadata;
        this.wrapperClassName = metadata.generatedClassName();
        this.componentNames = ComponentNames.withoutRenaming();
    }

    public void generate() throws IOException {
        TypeSpec.Builder typeSpecBuilder = TypeSpec.classBuilder(wrapperClassName.simpleName())
            .addOriginatingElement(metadata.element())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ClassNames.GENERATED_COMPONENT_MANAGER_HOLDER)
            .addMethod(createConstructor())
            .addField(componentManagerField())
            .addField(createLockObjectField())
            .addField(createSingletonField())
            .addMethod(addGetComponentManager())
            .addMethod(addGeneratedComponent())
            .addMethod(addStaticComponentGet());

        Generators.copyLintAnnotations(metadata.element(), typeSpecBuilder);
        Generators.copySuppressAnnotations(metadata.element(), typeSpecBuilder);
        Generators.addComponentOverride(metadata, typeSpecBuilder);

        Generators.addGeneratedBaseClassJavadoc(typeSpecBuilder, AndroidClassNames.HILT_DYNAMIC_FEATURE);
        Processors.addGeneratedAnnotation(typeSpecBuilder, env, getClass());

        JavaFile.builder(ClassName.get(MoreElements.asType(metadata.element())).packageName(), typeSpecBuilder.build())
            .build()
            .writeTo(env.getFiler());
    }

    private MethodSpec addStaticComponentGet() {
        return MethodSpec.methodBuilder("getGeneratedComponent")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(TypeName.OBJECT)
            .addParameter(AndroidClassNames.CONTEXT, "context")
            .beginControlFlow("if (INSTANCE == null)")
            .beginControlFlow("synchronized(LOCK)")
            .beginControlFlow("if (INSTANCE == null)")
            .addStatement("INSTANCE = new $T(context)", wrapperClassName)
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("return INSTANCE")
            .build();
    }

    // private static Hilt_$DYNAMIC INSTANCE = null;
    private FieldSpec createSingletonField() {
        return FieldSpec.builder(wrapperClassName, "INSTANCE", Modifier.PRIVATE, Modifier.STATIC)
            .initializer("null")
            .build();
    }

    // private static final Object LOCK = new Object();
    private FieldSpec createLockObjectField() {
        return FieldSpec.builder(TypeName.OBJECT, "LOCK", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer(CodeBlock.of("new Object()"))
            .build();
    }

    private MethodSpec addGetComponentManager() {
        return MethodSpec.methodBuilder("componentManager")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(ClassNames.GENERATED_COMPONENT_MANAGER, TypeName.OBJECT))
            .addStatement(CodeBlock.of("return componentManager"))
            .build();
    }

    private FieldSpec componentManagerField() {
        return FieldSpec.builder(AndroidClassNames.DYNAMIC_FEATURE_COMPONENT_MANAGER, "componentManager")
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .build();
    }

    private MethodSpec createConstructor() {
        return MethodSpec.constructorBuilder()
            .addParameter(AndroidClassNames.CONTEXT, "context")
            .addStatement(createRootComponent())
            .build();
    }

    private CodeBlock createRootComponent() {
        return CodeBlock.of(
            "componentManager = new $T($L)",
            AndroidClassNames.DYNAMIC_FEATURE_COMPONENT_MANAGER,
            createComponentManager()
        );
    }

    private TypeSpec createComponentManager() {
        ClassName component = componentNames.generatedComponent(
            ClassName.get(MoreElements.asType(metadata.element())), AndroidClassNames.DYNAMIC_FEATURE_COMPONENT
        );
        return TypeSpec.anonymousClassBuilder("")
            .addSuperinterface(AndroidClassNames.COMPONENT_SUPPLIER)
            .addMethod(
                    MethodSpec.methodBuilder("get")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(TypeName.OBJECT)
                        .addStatement(
                            "return $T.builder().applicationContextModule(new $T(context.getApplicationContext()))$L.build()",
                            Processors.prepend(Processors.getEnclosedClassName(component), "Dagger"),
                            AndroidClassNames.APPLICATION_CONTEXT_MODULE,
                            unrollComponentDependencies()
                        )
                        .build()
            )
            .build();
    }

    private CodeBlock unrollComponentDependencies() {
        AnnotationMirror mirror = metadata.element().getAnnotationMirrors().stream().filter(annotationMirror ->
            AndroidClassNames.HILT_DYNAMIC_FEATURE.equals(ClassName.get(annotationMirror.getAnnotationType()))
        ).findFirst().orElseThrow(IllegalStateException::new);

        List<Object> dependencyTypes = (List<Object>) mirror.getElementValues()
                .entrySet()
                .stream()
                .filter(entry -> "dependencies".equals(entry.getKey().getSimpleName()))
                .findFirst()
                .orElseThrow(IllegalAccessError::new)
                .getValue();

        List<CodeBlock> blocks = dependencyTypes.stream().map(annotation -> {
            TypeMirror type = (TypeMirror) ((AnnotationValue) annotation).getValue();
            String[] parts = type.toString().split("\\.");

            String camel = toCamelCase(parts[parts.length - 1]);
            return CodeBlock.of("." + camel + "($L)", buildEntryPointFor(type));
        }).collect(Collectors.toList());

        return CodeBlock.join(blocks, "\n");
    }

    private String toCamelCase(String other) {
        char ch = other.charAt(0);
        if (Character.isUpperCase(ch)) {
            return ch + other.toLowerCase(Locale.ROOT).substring(1);
        }
        return other;
    }

    private CodeBlock buildEntryPointFor(TypeMirror type) {
        return CodeBlock.of(
            "$.fromApplication(context, $T.class)",
            AndroidClassNames.ENTRY_POINT_ACCESSORS,
            type
        );
    }

    private MethodSpec addGeneratedComponent() {
        return MethodSpec.methodBuilder("generatedComponent")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .returns(TypeName.OBJECT)
            .addStatement(
                "return $L.generatedComponent()",
                componentManagerCallBlock()
            )
            .build();
    }

    private CodeBlock componentManagerCallBlock() {
        return CodeBlock.of(
            "$L.componentManager()",
            "this"
        );
    }
}
