package org.evomaster.client.java.instrumentation.object;

import org.evomaster.client.java.instrumentation.staticstate.ExecutionTracer;
import org.evomaster.client.java.instrumentation.staticstate.UnitsInfoRecorder;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.evomaster.client.java.instrumentation.shared.ClassToSchemaUtils.OPENAPI_REF_PATH;

/**
 * The SUT when getting data, it might unmarshal/deserialize it into a specif class.
 * Typical case is when a HTTP body payload is a JSON object, which should match a DTO
 * in the SUT.
 * So, we need a way to represent the schema of an object, independently from Java.
 * So, for simplicity, we use OpenAPI.
 * <br>
 * Note: the marshalling can depend on the actual used libraries, eg Jackson and GSON, as
 * they might use special annotations on the DTOs.
 */
public class ClassToSchema {

        /*
            https://github.com/OAI/OpenAPI-Specification/blob/3.0.1/versions/3.0.1.md#dataTypeFormat

        Common Name	    type	format	Comments
        integer	        integer	int32	signed 32 bits
        long	        integer	int64	signed 64 bits
        float	        number	float
        double	        number	double
        string	        string
        byte	        string	byte	base64 encoded characters
        binary	        string	binary	any sequence of octets
        boolean	        boolean
        date	        string	date	As defined by full-date - RFC3339
        dateTime	    string	date-time	As defined by date-time - RFC3339
        password	    string	password	Used to hint UIs the input needs to be obscured.
         */

    /**
     * Key -> DTO class
     * Value -> its schema representation, as OpenAPI JSON object
     * WARNING: this is a mutable static state, but, being just a cache, should not hopefully
     * have any nasty negative side-effects.
     */
    private static final Map<Type, String> cache = new ConcurrentHashMap<>();

    private static final String fieldRefPrefix = "{\"$ref\":\"";

    private static final String fieldRefPostfix = "\"}";

    public static void registerSchemaIfNeeded(Class<?> valueType) {

        if (valueType == null) {
            return;
        }

        if(valueType.getName().startsWith("io.swagger.")){
            //no point in dealing with this.
            //also it happens in E2E, where it leads to a infinite recursion
            return;
        }

        String name = valueType.getName();
        if (!UnitsInfoRecorder.isDtoSchemaRegister(name)){
            List<Class<?>> embedded = new ArrayList<>();
            String schema = ClassToSchema.getOrDeriveSchema(valueType, embedded);
            UnitsInfoRecorder.registerNewParsedDto(name, schema);
            ExecutionTracer.addParsedDtoName(name);
            if (!embedded.isEmpty()){
                embedded.forEach(ClassToSchema::registerSchemaIfNeeded);
            }

        }
    }
    /**
     *
     * @return a schema representation of the class along with schemas of its ref classes in the form
     *      "name: { "type name": "schema", "type name": "schema", .... }"
     */
    public static String getOrDeriveSchemaWithItsRef(Class<?> klass){
        StringBuilder sb = new StringBuilder();
        List<Class<?>> nested = new ArrayList<>();
        String schema = ClassToSchema.getOrDeriveSchemaAndNestedClasses(klass, nested);
        sb.append("{");
        sb.append(schema);

        nested.forEach(s->
                sb.append(",").append(ClassToSchema.getOrDeriveNonNestedSchema(s)));

        sb.append("}");
        return named(klass.getName(), sb.toString());
    }

    /**
     * @param nested is a list of nested classes
     * @return a schema representation of the class in the form "name: {...}", ie
     * like a field entry in an OpenAPI object definition
     */
    public static String getOrDeriveSchema(Class<?> klass, List<Class<?>> nested) {
        return getOrDeriveSchema(klass.getName(), klass, false, nested);
    }

    /**
     *
     * @return a schema representation of the class in the form "name: {...}"
     */
    public static String getOrDeriveNonNestedSchema(Class<?> klass) {
        return getOrDeriveSchema(klass.getName(), klass, false, Collections.emptyList());
    }

    private static String getOrDeriveSchema(String name, Type type, Boolean useRefObject, List<Class<?>> nested) {

        if (cache.containsKey(type) && !useRefObject) {
            return named(name, cache.get(type));
        }

        String schema = getSchema(type, useRefObject, nested, false);

        /*
            we only put the complete schema into schema
         */
        if (!schema.startsWith(fieldRefPrefix))
            cache.put(type, schema);
        return named(name, schema);
    }

    /**
     * @return a schema representation of the given class in the form "name: {...}" and add all its nested class into nested
     */
    public static String getOrDeriveSchemaAndNestedClasses(Class<?> klass, List<Class<?>> nested) {

        String schema = getSchema(klass, false, nested, true);

        return named(klass.getName(), schema);
    }

    private static String named(String name, String jsonObject) {
        return "\"" + name + "\":" + jsonObject;
    }


    /**
     *
     * @param useRefObject represents whether to represent the object with ref
     * @param nested is a list of nested classes
     * @param allNested represents whether to add all nested into [nested]
     */
    private static String getSchema(Type type, Boolean useRefObject, List<Class<?>> nested, boolean allNested) {

        Class<?> klass = null;
        if (type instanceof Class) {
            klass = (Class<?>) type;
        }
        ParameterizedType pType = null;
        if (type instanceof ParameterizedType) {
            pType = (ParameterizedType) type;
        }

        if (klass != null) {
            if (String.class.isAssignableFrom(klass)) {
                return fieldSchema("string");
            }

            if (Byte.class.isAssignableFrom(klass) || Byte.TYPE == klass) {
                return fieldSchema("integer", "int8");
            }
            if (Short.class.isAssignableFrom(klass) || Short.TYPE == klass) {
                return fieldSchema("integer", "int16");
            }
            if (Integer.class.isAssignableFrom(klass) || Integer.TYPE == klass) {
                return fieldSchema("integer", "int32");
            }
            if (Long.class.isAssignableFrom(klass) || Long.TYPE == klass) {
                return fieldSchema("integer", "int64");
            }
            if (Float.class.isAssignableFrom(klass) || Float.TYPE == klass) {
                return fieldSchema("number", "float");
            }
            if (Double.class.isAssignableFrom(klass) || Double.TYPE == klass) {
                return fieldSchema("number", "double");
            }
            if (Boolean.class.isAssignableFrom(klass) || Boolean.TYPE == klass) {
                return fieldSchema("boolean");
            }
            if (BigDecimal.class.isAssignableFrom(klass)) {
                //TODO not 100% sure this is correct...
                return fieldSchema("integer", "int64");
            }
        }
        //TODO date fields


        if ((klass != null && (klass.isArray() || List.class.isAssignableFrom(klass) || Set.class.isAssignableFrom(klass)))
                ||
                (pType != null && (List.class.isAssignableFrom((Class) pType.getRawType()) || Set.class.isAssignableFrom((Class) pType.getRawType())))) {
            return fieldArraySchema(klass, pType, nested, allNested);
        }

        //TOOD Map


        if (useRefObject){
            // register this class
            if ((allNested || !UnitsInfoRecorder.isDtoSchemaRegister(klass.getName())) && !nested.contains(klass))
                nested.add(klass);
            return fieldObjectRefSchema(klass.getName());
        }


        List<String> properties = new ArrayList<>();

        //general object, let's look at its fields
        Class<?> target = klass;
        while (target != null) {
            for (Field f : target.getDeclaredFields()) {
                if (!shouldAddToSchema(f)) {
                    continue;
                }
                String fieldName = getName(f);
                String fieldSchema = null;
                if (allNested){
                    fieldSchema = named(fieldName, getSchema(f.getGenericType(), true, nested, true));
                }else
                    fieldSchema = getOrDeriveSchema(fieldName, f.getGenericType(), true, nested);
                properties.add(fieldSchema);
            }
            target = target.getSuperclass();
        }

        return fieldObjectSchema(properties);
    }

    private static boolean shouldAddToSchema(Field field) {

        if (Modifier.isStatic(field.getModifiers()) ||
                Modifier.isTransient(field.getModifiers())) {
            return false;
        }

        /*
            Check annotations. However, it is bit tricky:
            - annotations could be on setters/getters, instead of the field
            - GSON is quite complex, as customizable, see
              https://www.baeldung.com/gson-exclude-fields-serialization
              Jackson should be easier
              https://fasterxml.github.io/jackson-annotations/javadoc/2.7/com/fasterxml/jackson/annotation/JsonIgnore.html

            anyway, worst case we are just adding fields that are going to be ignored.
            So, for now, just a simple check on annotation names should be fine.
            TODO: check if worthy if to have a full 100% support for Jackson and GSON
         */
        for (Annotation a : field.getAnnotations()) {
            String name = a.annotationType().getSimpleName();
            if (name.equalsIgnoreCase("Ignore")
                    || name.equalsIgnoreCase("Ignored")
                    || name.equalsIgnoreCase("Exclude")
                    || name.equalsIgnoreCase("Excluded")
                    || name.equalsIgnoreCase("JsonIgnore")
                    || name.equalsIgnoreCase("Skip")
                    || name.equalsIgnoreCase("Transient")) {
                return false;
            }
        }

        return true;
    }

    private static String getName(Field field) {
        //TODO there are other ways to define names
        for (Annotation a : field.getAnnotations()) {
            String name = a.annotationType().getName();
            if (name.equals("com.fasterxml.jackson.annotation.JsonProperty")
                    || name.equals("com.google.gson.annotations.SerializedName")) {
                try {
                    Method m = a.annotationType().getMethod("value");
                    String value = (String) m.invoke(a);
                    if (value != null && !value.isEmpty()) {
                        return value;
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return field.getName();
    }

    private static String fieldArraySchema(Class<?> klass, ParameterizedType pType, List<Class<?>> embedded, boolean allEmbedded) {

        String item;

        if (klass != null) {
            if (klass.isArray()) {
                item = getSchema(klass.getComponentType(), true, embedded, allEmbedded);
            } else {
                /*
                    This would happen if we have non-generic List or Set?
                    What to do? I guess can just use String
                 */
                item = getSchema(String.class,true, embedded, allEmbedded);
            }
        } else {
            //either List<> or Set<>
            Type generic = pType.getActualTypeArguments()[0];
            item = getSchema(generic,true, embedded, allEmbedded);
        }

        return "{\"type\":\"array\", \"items\":" + item + "}";
    }

    private static String fieldObjectSchema(List<String> properties) {
        String p = properties.stream().collect(Collectors.joining(","));

        return "{\"type\":\"object\", \"properties\": {" + p + "}}";
    }

    private static String fieldObjectRefSchema(String name) {
        return fieldRefPrefix + OPENAPI_REF_PATH + name + fieldRefPostfix;
    }

    private static String fieldSchema(String type) {
        return "{\"type\":\"" + type + "\"}";
    }

    private static String fieldSchema(String type, String format) {
        return "{\"type\":\"" + type + "\", \"format\":\"" + format + "\"}";
    }
}
