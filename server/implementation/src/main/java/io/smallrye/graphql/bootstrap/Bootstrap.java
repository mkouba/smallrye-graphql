package io.smallrye.graphql.bootstrap;

import static graphql.schema.visibility.DefaultGraphqlFieldVisibility.DEFAULT_FIELD_VISIBILITY;
import static graphql.schema.visibility.NoIntrospectionGraphqlFieldVisibility.NO_INTROSPECTION_FIELD_VISIBILITY;
import static io.smallrye.graphql.SmallRyeGraphQLServerLogging.log;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.json.Json;
import javax.json.JsonReader;
import javax.json.JsonReaderFactory;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;

import org.dataloader.BatchLoaderWithContext;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderRegistry;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.visibility.BlockedFields;
import graphql.schema.visibility.GraphqlFieldVisibility;
import io.smallrye.graphql.execution.Classes;
import io.smallrye.graphql.execution.batchloader.SourceBatchLoader;
import io.smallrye.graphql.execution.batchloader.SourceBatchLoaderHelper;
import io.smallrye.graphql.execution.context.SmallRyeContext;
import io.smallrye.graphql.execution.datafetcher.BatchDataFetcher;
import io.smallrye.graphql.execution.datafetcher.CollectionCreator;
import io.smallrye.graphql.execution.datafetcher.PropertyDataFetcher;
import io.smallrye.graphql.execution.datafetcher.ReflectionDataFetcher;
import io.smallrye.graphql.execution.error.ErrorInfoMap;
import io.smallrye.graphql.execution.event.EventEmitter;
import io.smallrye.graphql.execution.resolver.InterfaceOutputRegistry;
import io.smallrye.graphql.execution.resolver.InterfaceResolver;
import io.smallrye.graphql.json.JsonInputRegistry;
import io.smallrye.graphql.scalar.GraphQLScalarTypes;
import io.smallrye.graphql.schema.model.Argument;
import io.smallrye.graphql.schema.model.Array;
import io.smallrye.graphql.schema.model.EnumType;
import io.smallrye.graphql.schema.model.Field;
import io.smallrye.graphql.schema.model.Group;
import io.smallrye.graphql.schema.model.InputType;
import io.smallrye.graphql.schema.model.InterfaceType;
import io.smallrye.graphql.schema.model.Operation;
import io.smallrye.graphql.schema.model.Reference;
import io.smallrye.graphql.schema.model.ReferenceType;
import io.smallrye.graphql.schema.model.Schema;
import io.smallrye.graphql.schema.model.Type;
import io.smallrye.graphql.spi.ClassloadingService;

/**
 * Bootstrap MicroProfile GraphQL
 * This create a graphql-java model from the smallrye model
 * 
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class Bootstrap {

    private final Schema schema;
    private final Config config;
    private final EventEmitter eventEmitter;

    private final Map<String, GraphQLEnumType> enumMap = new HashMap<>();
    private final Map<String, GraphQLInterfaceType> interfaceMap = new HashMap<>();
    private final Map<String, GraphQLInputObjectType> inputMap = new HashMap<>();
    private final Map<String, GraphQLObjectType> typeMap = new HashMap<>();

    private GraphQLSchema graphQLSchema;
    private final DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();
    private final GraphQLCodeRegistry.Builder codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry();

    private final ClassloadingService classloadingService = ClassloadingService.get();

    public static BootstrapedResult bootstrap(Schema schema) {
        return bootstrap(schema, null);
    }

    public static BootstrapedResult bootstrap(Schema schema, Config config) {
        if (schema != null && (schema.hasOperations())) {
            Bootstrap bootstrap = new Bootstrap(schema, config);
            bootstrap.generateGraphQLSchema();
            return new BootstrapedResult(bootstrap.graphQLSchema, bootstrap.dataLoaderRegistry);
        } else {
            log.emptyOrNullSchema();
            return new BootstrapedResult();
        }
    }

    private Bootstrap(Schema schema, Config config) {
        this.schema = schema;
        this.config = config;
        this.eventEmitter = EventEmitter.getInstance(config);
        SmallRyeContext.setSchema(schema);
    }

    private void generateGraphQLSchema() {
        GraphQLSchema.Builder schemaBuilder = GraphQLSchema.newSchema();

        createGraphQLEnumTypes();
        createGraphQLInterfaceTypes();
        createGraphQLObjectTypes();
        createGraphQLInputObjectTypes();

        addQueries(schemaBuilder);
        addMutations(schemaBuilder);

        schemaBuilder.additionalTypes(new HashSet<>(enumMap.values()));
        schemaBuilder.additionalTypes(new HashSet<>(interfaceMap.values()));
        schemaBuilder.additionalTypes(new HashSet<>(typeMap.values()));
        schemaBuilder.additionalTypes(new HashSet<>(inputMap.values()));

        this.codeRegistryBuilder.fieldVisibility(getGraphqlFieldVisibility());
        schemaBuilder = schemaBuilder.codeRegistry(codeRegistryBuilder.build());

        // register error info
        ErrorInfoMap.register(schema.getErrors());

        // Allow custom extension
        schemaBuilder = eventEmitter.fireBeforeSchemaBuild(schemaBuilder);

        this.graphQLSchema = schemaBuilder.build();
    }

    private void addQueries(GraphQLSchema.Builder schemaBuilder) {
        GraphQLObjectType.Builder queryBuilder = GraphQLObjectType.newObject()
                .name(QUERY)
                .description(QUERY_DESCRIPTION);

        if (schema.hasQueries()) {
            addRootObject(queryBuilder, schema.getQueries(), QUERY);
        }
        if (schema.hasGroupedQueries()) {
            addGroupedRootObject(queryBuilder, schema.getGroupedQueries(), QUERY);
        }

        GraphQLObjectType query = queryBuilder.build();
        schemaBuilder.query(query);
    }

    private void addMutations(GraphQLSchema.Builder schemaBuilder) {
        GraphQLObjectType.Builder mutationBuilder = GraphQLObjectType.newObject()
                .name(MUTATION)
                .description(MUTATION_DESCRIPTION);

        if (schema.hasMutations()) {
            addRootObject(mutationBuilder, schema.getMutations(), MUTATION);
        }
        if (schema.hasGroupedMutations()) {
            addGroupedRootObject(mutationBuilder, schema.getGroupedMutations(), MUTATION);
        }

        GraphQLObjectType mutation = mutationBuilder.build();
        if (mutation.getFieldDefinitions() != null && !mutation.getFieldDefinitions().isEmpty()) {
            schemaBuilder.mutation(mutation);
        }
    }

    private void addRootObject(GraphQLObjectType.Builder rootBuilder, Set<Operation> operations,
            String rootName) {

        for (Operation operation : operations) {
            operation = eventEmitter.fireCreateOperation(operation);
            GraphQLFieldDefinition graphQLFieldDefinition = createGraphQLFieldDefinitionFromOperation(rootName,
                    operation);
            rootBuilder.field(graphQLFieldDefinition);
        }
    }

    private void addGroupedRootObject(GraphQLObjectType.Builder rootBuilder,
            Map<Group, Set<Operation>> operationMap, String rootName) {
        Set<Map.Entry<Group, Set<Operation>>> operationsSet = operationMap.entrySet();

        for (Map.Entry<Group, Set<Operation>> operationsEntry : operationsSet) {
            Group group = operationsEntry.getKey();
            Set<Operation> operations = operationsEntry.getValue();

            GraphQLObjectType namedType = createNamedType(rootName, group, operations);

            GraphQLFieldDefinition.Builder graphQLFieldDefinitionBuilder = GraphQLFieldDefinition.newFieldDefinition()
                    .name(group.getName()).description(group.getDescription());

            graphQLFieldDefinitionBuilder.type(namedType);

            // DataFetcher (Just a dummy)
            DataFetcher datafetcher = new DataFetcher() {
                public Object get(DataFetchingEnvironment dfe) throws Exception {
                    return namedType.getName();
                }
            };

            GraphQLFieldDefinition namedField = graphQLFieldDefinitionBuilder.build();

            this.codeRegistryBuilder.dataFetcherIfAbsent(
                    FieldCoordinates.coordinates(rootName, namedField.getName()),
                    datafetcher);

            rootBuilder.field(namedField);
        }
    }

    private GraphQLObjectType createNamedType(String parent, Group group, Set<Operation> operations) {
        String namedTypeName = group.getName() + parent;
        GraphQLObjectType.Builder objectTypeBuilder = GraphQLObjectType.newObject()
                .name(namedTypeName)
                .description(group.getDescription());

        // Operations
        for (Operation operation : operations) {
            String name = operation.getName();
            operation = eventEmitter.fireCreateOperation(operation);

            GraphQLFieldDefinition graphQLFieldDefinition = createGraphQLFieldDefinitionFromOperation(namedTypeName,
                    operation);
            objectTypeBuilder = objectTypeBuilder.field(graphQLFieldDefinition);
        }

        GraphQLObjectType graphQLObjectType = objectTypeBuilder.build();

        return graphQLObjectType;

    }

    // Create all enums and map them
    private void createGraphQLEnumTypes() {
        if (schema.hasEnums()) {
            for (EnumType enumType : schema.getEnums().values()) {
                createGraphQLEnumType(enumType);
            }
        }
    }

    private void createGraphQLEnumType(EnumType enumType) {
        GraphQLEnumType.Builder enumBuilder = GraphQLEnumType.newEnum()
                .name(enumType.getName())
                .description(enumType.getDescription());
        // Values
        for (String value : enumType.getValues()) {
            enumBuilder = enumBuilder.value(value);
        }
        GraphQLEnumType graphQLEnumType = enumBuilder.build();
        enumMap.put(enumType.getClassName(), graphQLEnumType);
    }

    private void createGraphQLInterfaceTypes() {
        if (schema.hasInterfaces()) {
            for (InterfaceType interfaceType : schema.getInterfaces().values()) {
                createGraphQLInterfaceType(interfaceType);
            }
        }
    }

    private void createGraphQLInterfaceType(InterfaceType interfaceType) {
        GraphQLInterfaceType.Builder interfaceTypeBuilder = GraphQLInterfaceType.newInterface()
                .name(interfaceType.getName())
                .description(interfaceType.getDescription());

        // Fields 
        if (interfaceType.hasFields()) {
            interfaceTypeBuilder = interfaceTypeBuilder
                    .fields(createGraphQLFieldDefinitionsFromFields(interfaceType.getName(),
                            interfaceType.getFields().values()));
        }

        // Interfaces
        if (interfaceType.hasInterfaces()) {
            Set<Reference> interfaces = interfaceType.getInterfaces();
            for (Reference i : interfaces) {
                interfaceTypeBuilder = interfaceTypeBuilder.withInterface(GraphQLTypeReference.typeRef(i.getName()));
            }
        }

        GraphQLInterfaceType graphQLInterfaceType = interfaceTypeBuilder.build();
        // To resolve the concrete class
        this.codeRegistryBuilder.typeResolver(graphQLInterfaceType, new InterfaceResolver(interfaceType));
        this.interfaceMap.put(interfaceType.getName(), graphQLInterfaceType);
    }

    private void createGraphQLInputObjectTypes() {
        if (schema.hasInputs()) {
            for (InputType inputType : schema.getInputs().values()) {
                createGraphQLInputObjectType(inputType);
            }
        }
    }

    private void createGraphQLInputObjectType(InputType inputType) {
        GraphQLInputObjectType.Builder inputObjectTypeBuilder = GraphQLInputObjectType.newInputObject()
                .name(inputType.getName())
                .description(inputType.getDescription());

        // Fields
        if (inputType.hasFields()) {
            inputObjectTypeBuilder = inputObjectTypeBuilder
                    .fields(createGraphQLInputObjectFieldsFromFields(inputType.getFields().values()));
            // Register this input for posible JsonB usage 
            JsonInputRegistry.register(inputType);
        }

        GraphQLInputObjectType graphQLInputObjectType = inputObjectTypeBuilder.build();
        inputMap.put(inputType.getName(), graphQLInputObjectType);
    }

    private void createGraphQLObjectTypes() {
        if (schema.hasTypes()) {
            for (Type type : schema.getTypes().values()) {
                createGraphQLObjectType(type);
            }
        }
    }

    private void createGraphQLObjectType(Type type) {
        GraphQLObjectType.Builder objectTypeBuilder = GraphQLObjectType.newObject()
                .name(type.getName())
                .description(type.getDescription());

        // Fields
        if (type.hasFields()) {
            objectTypeBuilder = objectTypeBuilder
                    .fields(createGraphQLFieldDefinitionsFromFields(type.getName(), type.getFields().values()));
        }

        // Operations
        if (type.hasOperations()) {
            for (Operation operation : type.getOperations().values()) {
                String name = operation.getName();
                if (!type.hasBatchOperation(name)) {
                    operation = eventEmitter.fireCreateOperation(operation);

                    GraphQLFieldDefinition graphQLFieldDefinition = createGraphQLFieldDefinitionFromOperation(type.getName(),
                            operation);
                    objectTypeBuilder = objectTypeBuilder.field(graphQLFieldDefinition);
                } else {
                    log.duplicateOperation(operation.getName());
                }
            }
        }

        // Batch Operations
        if (type.hasBatchOperations()) {
            for (Operation operation : type.getBatchOperations().values()) {
                operation = eventEmitter.fireCreateOperation(operation);

                GraphQLFieldDefinition graphQLFieldDefinition = createGraphQLFieldDefinitionFromBatchOperation(type.getName(),
                        operation);
                objectTypeBuilder = objectTypeBuilder.field(graphQLFieldDefinition);
            }
        }

        // Interfaces
        if (type.hasInterfaces()) {
            Set<Reference> interfaces = type.getInterfaces();
            for (Reference i : interfaces) {
                if (interfaceMap.containsKey(i.getName())) {
                    GraphQLInterfaceType graphQLInterfaceType = interfaceMap.get(i.getName());
                    objectTypeBuilder = objectTypeBuilder.withInterface(graphQLInterfaceType);
                }
            }
        }

        GraphQLObjectType graphQLObjectType = objectTypeBuilder.build();
        typeMap.put(type.getName(), graphQLObjectType);

        // Register this output for interface type resolving
        InterfaceOutputRegistry.register(type, graphQLObjectType);
    }

    private GraphQLFieldDefinition createGraphQLFieldDefinitionFromBatchOperation(String operationTypeName,
            Operation operation) {
        // Fields
        GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                .name(operation.getName())
                .description(operation.getDescription());

        // Return field
        fieldBuilder = fieldBuilder.type(createGraphQLOutputType(operation));

        // Arguments
        if (operation.hasArguments()) {
            fieldBuilder = fieldBuilder.arguments(createGraphQLArguments(operation.getArguments()));
        }

        registerBatchLoader(operation, config);

        DataFetcher<?> datafetcher = new BatchDataFetcher<>(operation, config);
        GraphQLFieldDefinition graphQLFieldDefinition = fieldBuilder.build();

        this.codeRegistryBuilder.dataFetcher(FieldCoordinates.coordinates(operationTypeName, graphQLFieldDefinition.getName()),
                datafetcher);

        return graphQLFieldDefinition;
    }

    private GraphQLFieldDefinition createGraphQLFieldDefinitionFromOperation(String operationTypeName, Operation operation) {
        // Fields
        GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                .name(operation.getName())
                .description(operation.getDescription());

        // Return field
        fieldBuilder = fieldBuilder.type(createGraphQLOutputType(operation));

        // Arguments
        if (operation.hasArguments()) {
            fieldBuilder = fieldBuilder.arguments(createGraphQLArguments(operation.getArguments()));
        }

        GraphQLFieldDefinition graphQLFieldDefinition = fieldBuilder.build();

        // DataFetcher
        DataFetcher<?> datafetcher = new ReflectionDataFetcher(operation, config);

        this.codeRegistryBuilder.dataFetcher(FieldCoordinates.coordinates(operationTypeName, graphQLFieldDefinition.getName()),
                datafetcher);

        return graphQLFieldDefinition;
    }

    private List<GraphQLFieldDefinition> createGraphQLFieldDefinitionsFromFields(String ownerName, Collection<Field> fields) {
        List<GraphQLFieldDefinition> graphQLFieldDefinitions = new ArrayList<>();
        for (Field field : fields) {
            graphQLFieldDefinitions.add(createGraphQLFieldDefinitionFromField(ownerName, field));
        }
        return graphQLFieldDefinitions;
    }

    private GraphQLFieldDefinition createGraphQLFieldDefinitionFromField(String ownerName, Field field) {
        GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                .name(field.getName())
                .description(field.getDescription());

        // Type
        fieldBuilder = fieldBuilder.type(createGraphQLOutputType(field));

        GraphQLFieldDefinition graphQLFieldDefinition = fieldBuilder.build();

        // DataFetcher
        PropertyDataFetcher datafetcher = new PropertyDataFetcher(field);
        this.codeRegistryBuilder.dataFetcher(FieldCoordinates.coordinates(ownerName, graphQLFieldDefinition.getName()),
                datafetcher);

        return graphQLFieldDefinition;
    }

    private List<GraphQLInputObjectField> createGraphQLInputObjectFieldsFromFields(Collection<Field> fields) {
        List<GraphQLInputObjectField> graphQLInputObjectFields = new ArrayList<>();
        for (Field field : fields) {
            graphQLInputObjectFields.add(createGraphQLInputObjectFieldFromField(field));
        }
        return graphQLInputObjectFields;
    }

    private GraphQLInputObjectField createGraphQLInputObjectFieldFromField(Field field) {
        GraphQLInputObjectField.Builder inputFieldBuilder = GraphQLInputObjectField.newInputObjectField()
                .name(field.getName())
                .description(field.getDescription());

        // Type
        inputFieldBuilder = inputFieldBuilder.type(createGraphQLInputType(field));

        // Default value (on method)
        inputFieldBuilder = inputFieldBuilder.defaultValue(sanitizeDefaultValue(field));

        return inputFieldBuilder.build();
    }

    private GraphQLInputType createGraphQLInputType(Field field) {

        GraphQLInputType graphQLInputType = referenceGraphQLInputType(field);

        // Collection
        if (field.hasArray()) {
            Array array = field.getArray();
            // Mandatory in the collection
            if (array.isNotEmpty()) {
                graphQLInputType = GraphQLNonNull.nonNull(graphQLInputType);
            }
            // Collection depth
            for (int i = 0; i < array.getDepth(); i++) {
                graphQLInputType = GraphQLList.list(graphQLInputType);
            }
        }

        // Mandatory
        if (field.isNotNull()) {
            graphQLInputType = GraphQLNonNull.nonNull(graphQLInputType);
        }

        return graphQLInputType;
    }

    private GraphQLOutputType createGraphQLOutputType(Field field) {
        GraphQLOutputType graphQLOutputType = referenceGraphQLOutputType(field);

        // Collection
        if (field.hasArray()) {
            Array array = field.getArray();
            // Mandatory in the collection
            if (array.isNotEmpty()) {
                graphQLOutputType = GraphQLNonNull.nonNull(graphQLOutputType);
            }
            // Collection depth
            for (int i = 0; i < array.getDepth(); i++) {
                graphQLOutputType = GraphQLList.list(graphQLOutputType);
            }
        }

        // Mandatory
        if (field.isNotNull()) {
            graphQLOutputType = GraphQLNonNull.nonNull(graphQLOutputType);
        }

        return graphQLOutputType;
    }

    private GraphQLOutputType referenceGraphQLOutputType(Field field) {
        Reference reference = getCorrectFieldReference(field);
        ReferenceType type = reference.getType();
        String className = reference.getClassName();
        String name = reference.getName();
        switch (type) {
            case SCALAR:
                return getCorrectScalarType(reference);
            case ENUM:
                return enumMap.get(className);
            default:
                return GraphQLTypeReference.typeRef(name);
        }
    }

    private GraphQLInputType referenceGraphQLInputType(Field field) {
        Reference reference = getCorrectFieldReference(field);
        ReferenceType type = reference.getType();
        String className = reference.getClassName();
        String name = reference.getName();
        switch (type) {
            case SCALAR:
                return getCorrectScalarType(reference);
            case ENUM:
                return enumMap.get(className);
            default:
                return GraphQLTypeReference.typeRef(name);
        }
    }

    private Reference getCorrectFieldReference(Field field) {
        // First check if this is mapped to some other type

        if (field.getReference().hasMappingInfo()) { // Global
            return field.getReference().getMappingInfo().getReference();
        } else if (field.hasMappingInfo()) { // Per field
            return field.getMappingInfo().getReference();
        } else {
            return field.getReference();
        }
    }

    private GraphQLScalarType getCorrectScalarType(Reference fieldReference) {
        return GraphQLScalarTypes.getScalarByName(fieldReference.getName());
    }

    private List<GraphQLArgument> createGraphQLArguments(List<Argument> arguments) {
        List<GraphQLArgument> graphQLArguments = new ArrayList<>();
        for (Argument argument : arguments) {
            if (!argument.isSourceArgument()) {
                graphQLArguments.add(createGraphQLArgument(argument));
            }
        }
        return graphQLArguments;
    }

    private GraphQLArgument createGraphQLArgument(Argument argument) {
        GraphQLArgument.Builder argumentBuilder = GraphQLArgument.newArgument()
                .name(argument.getName())
                .description(argument.getDescription())
                .defaultValue(sanitizeDefaultValue(argument));

        GraphQLInputType graphQLInputType = referenceGraphQLInputType(argument);

        // Collection
        if (argument.hasArray()) {
            Array array = argument.getArray();
            // Mandatory in the collection
            if (array.isNotEmpty()) {
                graphQLInputType = GraphQLNonNull.nonNull(graphQLInputType);
            }
            // Collection depth
            for (int i = 0; i < array.getDepth(); i++) {
                graphQLInputType = GraphQLList.list(graphQLInputType);
            }
        }

        // Mandatory
        if (argument.isNotNull()) {
            graphQLInputType = GraphQLNonNull.nonNull(graphQLInputType);
        }

        argumentBuilder = argumentBuilder.type(graphQLInputType);

        return argumentBuilder.build();

    }

    private Object sanitizeDefaultValue(Field field) {
        String jsonString = field.getDefaultValue();

        if (jsonString == null) {
            return null;
        }

        if (isJsonString(jsonString)) {
            Class<?> type;
            if (field.hasArray()) {
                type = classloadingService.loadClass(field.getArray().getClassName());
                if (Collection.class.isAssignableFrom(type)) {
                    type = CollectionCreator.newCollection(field.getArray().getClassName()).getClass();
                }
            } else {
                type = classloadingService.loadClass(field.getReference().getClassName());
            }
            return JSONB.fromJson(jsonString, type);
        }

        if (Classes.isNumberLikeType(field.getReference().getGraphQlClassName())) {
            return new BigDecimal(jsonString);
        }

        if (Classes.isBoolean(field.getReference().getGraphQlClassName())) {
            return Boolean.parseBoolean(jsonString);
        }

        return jsonString;
    }

    private boolean isJsonString(String string) {
        if (string != null && !string.isEmpty() && (string.contains("{") || string.contains("["))) {
            try (StringReader stringReader = new StringReader(string);
                    JsonReader jsonReader = jsonReaderFactory.createReader(stringReader)) {

                jsonReader.readValue();
                return true;
            } catch (Exception ex) {
                // Not a valid json
            }
        }
        return false;
    }

    /**
     * This can hide certain fields in the schema (for security purposes)
     * 
     * @return The visibility
     * @see www.graphql-java.com/documentation/v15/fieldvisibility/
     */
    private GraphqlFieldVisibility getGraphqlFieldVisibility() {
        if (config != null) {
            String fieldVisibility = config.getFieldVisibility();
            if (fieldVisibility != null && !fieldVisibility.isEmpty()) {

                if (fieldVisibility.equals(Config.FIELD_VISIBILITY_NO_INTROSPECTION)) {
                    return NO_INTROSPECTION_FIELD_VISIBILITY;
                } else {
                    String[] patterns = fieldVisibility.split(COMMA);
                    BlockedFields.Builder blockedFields = BlockedFields.newBlock();
                    for (String pattern : patterns) {
                        blockedFields = blockedFields.addPattern(pattern);
                    }
                    return blockedFields.build();
                }
            }
        }
        return DEFAULT_FIELD_VISIBILITY;
    }

    public void registerBatchLoader(Operation operation, Config config) {
        BatchLoaderWithContext<Object, Object> batchLoader = new SourceBatchLoader(operation, config);
        this.dataLoaderRegistry.register(SourceBatchLoaderHelper.getName(operation), DataLoader.newDataLoader(batchLoader));
    }

    public void registerDataLoader(String name, DataLoader<?, ?> dataLoader) {
        this.dataLoaderRegistry.register(name, dataLoader);
    }

    private static final String QUERY = "Query";
    private static final String QUERY_DESCRIPTION = "Query root";

    private static final String MUTATION = "Mutation";
    private static final String MUTATION_DESCRIPTION = "Mutation root";

    private static final String COMMA = ",";

    private static final Jsonb JSONB = JsonbBuilder.create();
    private static final JsonReaderFactory jsonReaderFactory = Json.createReaderFactory(null);
}
