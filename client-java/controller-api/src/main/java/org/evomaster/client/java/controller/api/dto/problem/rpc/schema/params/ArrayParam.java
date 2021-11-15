package org.evomaster.client.java.controller.api.dto.problem.rpc.schema.params;

import org.evomaster.client.java.controller.api.dto.problem.rpc.schema.types.CollectionType;

import java.lang.reflect.Array;
import java.util.List;

/**
 * created by manzhang on 2021/11/15
 */
public class ArrayParam extends NamedTypedValue<CollectionType, Array>{
    List<NamedTypedValue> values;

    public ArrayParam(String name, CollectionType type) {
        super(name, type);
    }

    @Override
    public Object newInstance() throws ClassNotFoundException {
        return values.stream().map(v-> {
            try {
                return v.newInstance();
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("ArrayParam: could not create new instance for value:"+v.getType());
            }
        }).toArray();
    }
}
