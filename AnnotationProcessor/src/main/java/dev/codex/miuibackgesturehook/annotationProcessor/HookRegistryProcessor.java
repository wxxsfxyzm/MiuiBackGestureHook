package dev.codex.miuibackgesturehook.annotationProcessor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SupportedAnnotationTypes(
        "dev.codex.miuibackgesturehook.util.Hooker.XposedHooker"
)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class HookRegistryProcessor extends AbstractProcessor {
    private static final String ANNOTATION = "dev.codex.miuibackgesturehook.util.Hooker.XposedHooker";
    private static final String GENERATED_PACKAGE = "dev.codex.miuibackgesturehook.util.generated";
    private static final String GENERATED_CLASS = "HookRegistry";

    private record HookEntry(String className, String name, int order, List<String> targets) {}

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        TypeElement xposedHooker = null;

        for (TypeElement annotation : annotations) {
            if (ANNOTATION.equals(annotation.getQualifiedName().toString())) {
                xposedHooker = annotation;
                break;
            }
        }

        if (xposedHooker == null) {
            return false;
        }

        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(xposedHooker);

        if (elements.isEmpty()) {
            return false;
        }

        List<HookEntry> entries = new ArrayList<>();

        for (Element element : elements) {
            if (element.getKind() != ElementKind.CLASS) {
                error(element, "@Hooker.XposedHooker can only be applied to classes");
                continue;
            }

            HookEntry entry = readHookEntry((TypeElement) element);

            if (entry != null) {
                entries.add(entry);
            }
        }

        if (entries.isEmpty()) {
            return false;
        }

        entries.sort(Comparator.comparingInt(HookEntry::order)
                .thenComparing(HookEntry::className));

        Map<String, HookEntry> unique = new LinkedHashMap<>();

        for (HookEntry entry : entries) {
            if (unique.put(entry.className(), entry) != null) {
                error(null, "Duplicate @Hooker.XposedHooker class: " + entry.className());
            }
        }

        generate(new ArrayList<>(unique.values()));

        return true;
    }

    private HookEntry readHookEntry(TypeElement type) {
        AnnotationMirror annotation = null;

        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            Element annotationElement = mirror.getAnnotationType().asElement();

            if (annotationElement instanceof TypeElement annotationType && ANNOTATION.equals(annotationType.getQualifiedName().toString())) {
                annotation = mirror;
                break;
            }
        }

        if (annotation == null) {
            return null;
        }

        String name = null;
        int order = 0;
        List<String> targets = new ArrayList<>();

        var values = processingEnv.getElementUtils().getElementValuesWithDefaults(annotation);

        for (var entry : values.entrySet()) {
            String key = entry.getKey().getSimpleName().toString();
            Object value = entry.getValue().getValue();

            switch (key) {
                case "name" -> {
                    if (value instanceof String string) {
                        name = string;
                    }
                }

                case "targets" -> {
                    if (value instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof AnnotationValue annotationValue) {
                                Object target =
                                        annotationValue.getValue();

                                if (target instanceof String string) {
                                    targets.add(string);
                                }
                            }
                        }
                    }
                }

                case "order" -> {
                    if (value instanceof Integer integer) {
                        order = integer;
                    }
                }
            }
        }

        if (name == null || name.isBlank()) {
            error(type, "@Hooker.XposedHooker on " + type.getQualifiedName() + " is missing name");
            return null;
        }

        if (targets.isEmpty()) {
            error(type, "@Hooker.XposedHooker on " + type.getQualifiedName() + " must declare at least one target");
            return null;
        }

        return new HookEntry(type.getQualifiedName().toString(), name, order,
                List.copyOf(targets));
    }

    private void generate(List<HookEntry> entries) {
        String qualifiedName = GENERATED_PACKAGE + "." + GENERATED_CLASS;
        Filer filer = processingEnv.getFiler();

        try {
            JavaFileObject file = filer.createSourceFile(qualifiedName);

            try (Writer writer = file.openWriter()) {
                writer.write(buildSource(entries));
            }

        } catch (IOException e) {
            error(null, "Failed to generate " + qualifiedName + ": " + e.getMessage());
        }
    }

    private String buildSource(List<HookEntry> entries) {
        StringBuilder out = new StringBuilder();

        out.append("package dev.codex.miuibackgesturehook.util.generated;\n");
        out.append("\n");
        out.append("import java.util.List;\n");
        out.append("import java.util.Set;\n");
        out.append("import dev.codex.miuibackgesturehook.util.Hooker;\n");
        out.append("\n");
        out.append("/** Auto generated file. DO NOT EDIT. */\n");
        out.append("public final class HookRegistry {\n");
        out.append("    private HookRegistry() {}\n");
        out.append("\n");
        out.append("    public record Entry(int id, String className, String name, Set<String> targets) {}\n");
        out.append("\n");
        out.append("    public static List<Entry> entries() {\n");
        out.append("        return List.of(\n");

        for (int index = 0; index < entries.size(); index++) {
            HookEntry entry = entries.get(index);

            String targets = entry.targets().stream()
                    .map(this::javaString)
                    .collect(Collectors.joining(", "));

            out.append("                new Entry(")
                    .append(index)
                    .append(", ")
                    .append(javaString(entry.className()))
                    .append(", ")
                    .append(javaString(entry.name()))
                    .append(", Set.of(")
                    .append(targets)
                    .append("))");

            if (index != entries.size() - 1) {
                out.append(",");
            }

            out.append("\n");
        }

        out.append("        );\n");
        out.append("    }\n");
        out.append("\n");
        out.append("    public static Hooker getHooker(int id) {\n");
        out.append("        return switch (id) {\n");

        for (int index = 0; index < entries.size(); index++) {
            HookEntry entry = entries.get(index);

            out.append("            case ")
                    .append(index)
                    .append(" -> new ")
                    .append(entry.className())
                    .append("();\n");
        }

        out.append("            default -> throw new IllegalArgumentException(\"Unknown hook id: \" + id);\n");
        out.append("        };\n");
        out.append("    }\n");
        out.append("}\n");

        return out.toString();
    }

    private String javaString(String value) {
        return "\""
                + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                + "\"";
    }

    private void error(Element element, String message) {
        if (element != null) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    message,
                    element
            );
        } else {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    message
            );
        }
    }
}
