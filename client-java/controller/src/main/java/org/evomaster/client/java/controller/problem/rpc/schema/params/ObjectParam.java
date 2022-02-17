package org.evomaster.client.java.controller.problem.rpc.schema.params;

import org.evomaster.client.java.controller.api.dto.problem.rpc.ParamDto;
import org.evomaster.client.java.controller.problem.rpc.CodeJavaGenerator;
import org.evomaster.client.java.controller.problem.rpc.schema.types.AccessibleSchema;
import org.evomaster.client.java.controller.problem.rpc.schema.types.ObjectType;
import org.evomaster.client.java.utils.SimpleLogger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * object param
 */
public class ObjectParam extends NamedTypedValue<ObjectType, List<NamedTypedValue>> {

    public ObjectParam(String name, ObjectType type, AccessibleSchema accessibleSchema) {
        super(name, type, accessibleSchema);
    }

    @Override
    public Object newInstance() throws ClassNotFoundException {
        if (getValue() == null) return null;
        String clazzName = getType().getFullTypeName();
        Class<?> clazz = Class.forName(clazzName);
        try {
            Object instance = clazz.newInstance();
            for (NamedTypedValue v: getValue()){
                Field f = clazz.getDeclaredField(v.getName());
                f.setAccessible(true);
                Object vins = v.newInstance();
                if (vins != null)
                    f.set(instance, vins);
            }
            return instance;
        } catch (InstantiationException e) {
            throw new RuntimeException("fail to construct the class:"+clazzName+" with error msg:"+e.getMessage());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("fail to access the class:"+clazzName+" with error msg:"+e.getMessage());
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("fail to access the field:"+clazzName+" with error msg:"+e.getMessage());
        }
    }

    @Override
    public ObjectParam copyStructure() {
        return new ObjectParam(getName(), getType(), accessibleSchema);
    }

    @Override
    public ParamDto getDto() {
        ParamDto dto = super.getDto();

        if (getValue() != null){
            dto.innerContent = getValue().stream().map(NamedTypedValue::getDto).collect(Collectors.toList());
            dto.stringValue = NOT_NULL_MARK_OBJ_DATE;
        } else
            dto.innerContent = getType().getFields().stream().map(NamedTypedValue::getDto).collect(Collectors.toList());
        return dto;
    }

    @Override
    public void setValueBasedOnDto(ParamDto dto) {

        if (dto.innerContent!=null && !dto.innerContent.isEmpty()){
            List<NamedTypedValue> fields = getType().getFields();
            List<NamedTypedValue> values = new ArrayList<>();

            for (ParamDto p: dto.innerContent){
                NamedTypedValue f = fields.stream().filter(s-> s.sameParam(p)).findFirst().get().copyStructure();
                f.setValueBasedOnDto(p);
                values.add(f);
            }

            setValue(values);
        }
    }

    @Override
    protected void setValueBasedOnValidInstance(Object instance) {
        List<NamedTypedValue> values = new ArrayList<>();
        List<NamedTypedValue> fields = getType().getFields();
        Class<?> clazz;
        try {
            clazz = Class.forName(getType().getFullTypeName());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("ERROR: fail to get class with the name"+getType().getFullTypeName()+" Msg:"+e.getMessage());
        }
        for (NamedTypedValue f: fields){
            NamedTypedValue copy = f.copyStructure();
            try {
                Field fi = clazz.getDeclaredField(f.getName());
                fi.setAccessible(true);
                Object fiv = fi.get(instance);
                copy.setValueBasedOnInstance(fiv);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("ERROR: fail to get value of the field with the name ("+ f.getName()+ ") and error Msg:"+e.getMessage());
            }

            values.add(copy);
        }

        setValue(values);
    }

    @Override
    public List<String> newInstanceWithJava(boolean isDeclaration, boolean doesIncludeName, String variableName, int indent) {
        String typeName = getType().getTypeNameForInstance();
        String varName = variableName;

        List<String> codes = new ArrayList<>();
        boolean isNull = (getValue() == null);
        String var = CodeJavaGenerator.oneLineInstance(isDeclaration, doesIncludeName, typeName, varName, null);
        CodeJavaGenerator.addCode(codes, var, indent);
        if (isNull) return codes;

        CodeJavaGenerator.addCode(codes, "{", indent);
        // new obj
        CodeJavaGenerator.addCode(codes, CodeJavaGenerator.setInstanceObject(typeName, varName), indent+1);
        for (NamedTypedValue f : getValue()){
            if (f.accessibleSchema == null || f.accessibleSchema.isAccessible){
                String fName = varName+"."+f.getName();
                codes.addAll(f.newInstanceWithJava(false, true, fName, indent+1));
            }else{
                String fName = varName;
                boolean fdeclar = false;
                if (f instanceof ObjectParam || f instanceof CollectionParam || f instanceof DateParam){
                     fName = varName+"_"+f.getName();
                     fdeclar = true;
                }
                codes.addAll(f.newInstanceWithJava(fdeclar, true, fName, indent+1));

                if (f instanceof ObjectParam || f instanceof CollectionParam || f instanceof DateParam){
                    CodeJavaGenerator.addCode(codes, CodeJavaGenerator.methodInvocation(varName, f.accessibleSchema.setterMethodName, fName)+CodeJavaGenerator.appendLast(),indent+1);
                }
            }
        }

        CodeJavaGenerator.addCode(codes, "}", indent);
        return codes;
    }

    @Override
    public List<String> newAssertionWithJava(int indent, String responseVarName, int maxAssertionForDataInCollection) {
        List<String> codes = new ArrayList<>();
        if (getValue() == null){
            CodeJavaGenerator.addCode(codes, CodeJavaGenerator.junitAssertNull(responseVarName), indent);
            return codes;
        }
        for (NamedTypedValue f : getValue()){
            String fName = null;
            if (f.accessibleSchema == null || f.accessibleSchema.isAccessible)
                fName = responseVarName+"."+f.getName();
            else{
                if (f.accessibleSchema.getterMethodName == null){
                    String msg = "Error: Object("+getType().getFullTypeName()+") has private field "+f.getName()+", but there is no getter method";
                    SimpleLogger.uniqueWarn(msg);
                    CodeJavaGenerator.addComment(codes, msg, indent);
                }else{
                    fName = responseVarName+"."+f.accessibleSchema.getterMethodName+"()";
                }
            }
            if (fName != null)
                codes.addAll(f.newAssertionWithJava(indent, fName, maxAssertionForDataInCollection));
        }
        return codes;
    }

    @Override
    public String getValueAsJavaString() {
        return null;
    }

}