package com.thrift.example.artificial;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * created by manzhang on 2021/11/27
 */
public class RPCInterfaceExampleImpl implements RPCInterfaceExample{
    @Override
    public GenericResponse array(List<String>[] args0) {
        GenericResponse response = new GenericResponse();
        response.info = Arrays.stream(args0).map(s-> String.join(",", s)).collect(Collectors.joining(";"));
        return response;
    }

    @Override
    public GenericResponse arrayboolean(boolean[] args0) {
        GenericResponse response = new GenericResponse();
        StringBuffer sb = new StringBuffer();
        for (boolean b : args0){
            sb.append(b+",");
        }
        sb.append("ARRAY_END");
        response.info = sb.toString();
        return response;
    }

    @Override
    public GenericResponse list(List<String> args0) {
        GenericResponse response = new GenericResponse();
        response.info = String.join(",", args0);
        return response;
    }

    @Override
    public GenericResponse map(Map<String, String> args0) {
        GenericResponse response = new GenericResponse();
        response.info = args0.entrySet().stream().map(s-> s.getKey()+":"+s.getValue()).collect(Collectors.joining(","));
        return response;
    }

    @Override
    public GenericResponse listAndMap(List<Map<String, String>> args0) {
        GenericResponse response = new GenericResponse();
        response.info = args0.stream()
                .map(l-> l.entrySet().stream().map(s-> s.getKey()+":"+s.getValue()).collect(Collectors.joining(",")))
                .collect(Collectors.joining(";"));
        return response;
    }

    @Override
    public ObjectResponse objResponse() {
        ObjectResponse response = new ObjectResponse();
        response.f1 = "foo";
        response.f2 = 42;
        response.f3 = 0.42;
        response.f4 = new double[]{0.0, 0.5, 1.0};
        return response;
    }
}
