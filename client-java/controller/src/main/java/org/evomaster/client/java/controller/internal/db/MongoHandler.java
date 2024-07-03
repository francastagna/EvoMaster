package org.evomaster.client.java.controller.internal.db;

import org.evomaster.client.java.controller.api.dto.database.execution.MongoFailedQuery;
import org.evomaster.client.java.controller.api.dto.database.execution.MongoExecutionDto;
import org.evomaster.client.java.controller.internal.TaintHandlerExecutionTracer;
import org.evomaster.client.java.controller.mongo.MongoHeuristicsCalculator;
import org.evomaster.client.java.controller.mongo.MongoOperation;
import org.evomaster.client.java.instrumentation.MongoCollectionSchema;
import org.evomaster.client.java.instrumentation.MongoFindCommand;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Class used to act upon Mongo commands executed by the SUT
 */
public class MongoHandler {

    /**
     * Info about Find operations executed
     */
    private final List<MongoFindCommand> operations;

    /**
     * Whether to use execution's info or not
     */
    private volatile boolean extractMongoExecution;

    /**
     * The heuristics based on the Mongo execution
     */
    private final List<MongoOperationDistance> distances;

    /**
     * Whether to calculate heuristics based on execution or not
     */
    private volatile boolean calculateHeuristics;

    /**
     * Unsuccessful executed queries
     */
    private final List<MongoOperation> failedQueries;

    /**
     * Info about schemas of the documents of the repository extracted from Spring framework.
     * Documents of the collection will be mapped to the Repository type
     */
    private final Map<String, String> collectionSchemas;

    /**
     * Since we do not want to add a dependency to given Mongo version, we
     * are using an Object reference
     */
    private Object mongoClient = null;

    private final MongoHeuristicsCalculator calculator = new MongoHeuristicsCalculator(new TaintHandlerExecutionTracer());

    public MongoHandler() {
        distances = new ArrayList<>();
        operations = new ArrayList<>();
        failedQueries = new ArrayList<>();
        collectionSchemas = new HashMap<>();
        extractMongoExecution = true;
        calculateHeuristics = true;
    }

    public void reset() {
        operations.clear();
        distances.clear();
        failedQueries.clear();
        // collectionInfo is not cleared to avoid losing the info as it's retrieved while SUT is starting
    }

    public boolean isCalculateHeuristics() {
        return calculateHeuristics;
    }

    public boolean isExtractMongoExecution() {
        return extractMongoExecution;
    }

    public void setCalculateHeuristics(boolean calculateHeuristics) {
        this.calculateHeuristics = calculateHeuristics;
    }

    public void setExtractMongoExecution(boolean extractMongoExecution) {
        this.extractMongoExecution = extractMongoExecution;
    }

    public void handle(MongoFindCommand info) {
        if (extractMongoExecution) {
            operations.add(info);
        }
    }

    public void handle(MongoCollectionSchema info) {
        if (extractMongoExecution) {
            collectionSchemas.put(info.getCollectionName(), info.getCollectionSchema());
        }
    }

    public List<MongoOperationDistance> getDistances() {

        operations.stream().filter(info -> info.getQuery() != null).forEach(mongoInfo -> {
            double dist;
            try {
                dist = computeDistance(mongoInfo);
            } catch (Exception e) {
                dist = Double.MAX_VALUE;
            }
            distances.add(new MongoOperationDistance(mongoInfo.getQuery(), dist));
        });
        operations.clear();

        return distances;
    }

    public MongoExecutionDto getExecutionDto() {
        MongoExecutionDto dto = new MongoExecutionDto();
        dto.failedQueries = failedQueries.stream().map(this::extractRelevantInfo).collect(Collectors.toList());
        return dto;
    }

    private static Class<?> getCollectionClass(Object collection) throws ClassNotFoundException {
        // collection is an implementation of interface MongoCollection
        return collection.getClass().getInterfaces()[0];
    }

    private Iterable<?> getDocuments(Object collection) {
        // Need to convert result of getDocuments which a FindIterable instance as it is not Serializable
        List<Object> documentsAsList = new ArrayList<>();
        try {
            Class<?> collectionClass = getCollectionClass(collection);
            Iterable<?> findIterable = (Iterable<?>) collectionClass.getMethod("find").invoke(collection);
            findIterable.forEach(documentsAsList::add);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException |
                 ClassNotFoundException e) {
            throw new RuntimeException("Failed to retrieve all documents from a mongo collection", e);
        }
        return documentsAsList;
    }

    private double computeDistance(MongoFindCommand info) {

        String databaseName= info.getDatabaseName();
        String collectionName = info.getCollectionName();

        Object collection = getCollection(databaseName,collectionName);
        Iterable<?> documents = getDocuments(collection);
        boolean collectionIsEmpty = !documents.iterator().hasNext();

        if (collectionIsEmpty) {
            failedQueries.add(new MongoOperation(info.getCollectionName(), info.getQuery(), info.getDatabaseName(), info.getDocumentsType()));
        }

        double min = Double.MAX_VALUE;

        for (Object doc : documents) {
            double dist = calculator.computeExpression(info.getQuery(), doc);
            if (dist == 0) return 0;
            if (dist < min) min = dist;
        }
        return min;
    }

    private Object getCollection(String databaseName, String collectionName) {
        try {
            // Get the MongoClient class
            Class<?> mongoClientClass = mongoClient.getClass();

            // Get the "getDatabase" method of class MongoClient
            Method getDatabaseMethod = mongoClientClass.getMethod("getDatabase", String.class);

            // mongoClient.getDatabase(databaseName)
            Object database = getDatabaseMethod.invoke(mongoClient, databaseName);

            // Get the MongoDatabase class
            Class<?> mongoDatabaseClass = database.getClass();

            // Get the "getCollection" method of class MongoCollection c
            Method getCollectionMethod = mongoDatabaseClass.getMethod("getCollection", String.class);

            // database.getCollection(collectionName)
            Object collection = getCollectionMethod.invoke(database, collectionName);

            return collection;

        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException("Failed to retrieve a Mongo collection instance", e);
        }

    }

    private MongoFailedQuery extractRelevantInfo(MongoOperation operation) {
        String documentsType;
        if (collectionSchemaIsRegistered(operation.getCollectionName())) {
            // We have to which class the documents of the collection will be mapped to
            documentsType = collectionSchemas.get(operation.getCollectionName());
        } else {
            // Just using the documents type provided by the MongoCollection method
            documentsType = operation.getDocumentsType();
        }
        return new MongoFailedQuery(operation.getDatabaseName(), operation.getCollectionName(), documentsType);
    }

    private boolean collectionSchemaIsRegistered(String collectionName) {
        return collectionSchemas.containsKey(collectionName);
    }

    public void setMongoClient(Object mongoClient) {
        this.mongoClient = mongoClient;
    }
}
