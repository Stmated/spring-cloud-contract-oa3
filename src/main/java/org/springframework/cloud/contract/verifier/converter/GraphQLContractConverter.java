package org.springframework.cloud.contract.verifier.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Iterables;
import graphql.language.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.contract.spec.Contract;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;

public class GraphQLContractConverter extends AbstractContractConverter<Collection<Document>> {

    private static final Logger log = LoggerFactory.getLogger(GraphQLContractConverter.class);

    private String url = "/graphql";

    @Override
    public boolean isAccepted(File file) {

        log.info("Checking if '" + file.getAbsolutePath() + "' is accepted");
        if (StringUtils.equalsAnyIgnoreCase(FilenameUtils.getExtension(file.getName()), "graphqls", "graphql")) {

            final File accompanyingMetaFile = getAccompanyingMetaFile(file);
            if (accompanyingMetaFile.exists()) {

                log.info("Accepted: '" + file.getAbsolutePath() + "'");
                return true;
            }
        }

        return false;
    }

    @NotNull
    private File getAccompanyingMetaFile(File file) {
        return new File(file.getParentFile(), FilenameUtils.getBaseName(file.getName()) + "_contract.yml");
    }

    @Override
    protected boolean isAcceptedRootJsonNode(JsonNode node) {
        return StringUtils.isNotBlank(node.path("graphql").asText(null));
    }

    @Override
    public Collection<Contract> convertFrom(File file) {

        final var contracts = new ArrayList<Contract>();

        try {

            // If we have "file.graphqls" then we will also read "file_contract.yml" which contains the openapi-like spec
            // For now we need to do this since the graphql schema is very hard to extend properly with custom parameters.
            // And writing the actual Spring Cloud Contract manually is too hard for many. Lots of correct setup can be required.
            final var metaFile = this.getAccompanyingMetaFile(file);
            for (final var spec : this.readNodes(metaFile)) {

                final var fileContent = FileUtils.readFileToString(file, StandardCharsets.UTF_8);

                final var document = graphql.parser.Parser.parse(fileContent);

                for (final var definition : document.getDefinitions()) {

                    if (definition instanceof SchemaDefinition) {
                        this.readSchemaDefinition(document, (SchemaDefinition) definition, spec, contracts);
                    }
                }
            }

        } catch (Exception ex) {
            throw new RuntimeException("Could not convert from file '" + file.getAbsolutePath() + "'", ex);
        }

        return contracts;
    }

    private void readSchemaDefinition(Document document, SchemaDefinition schema, JsonNode spec, Collection<Contract> contracts) {


//        else if (definition instanceof ObjectTypeDefinition) {
//
//            final ObjectTypeDefinition type = (ObjectTypeDefinition) definition;
//
//        } else if (definition instanceof InputObjectTypeDefinition) {
//
//            final InputObjectTypeDefinition type = (InputObjectTypeDefinition) definition;
//
//        } else {
//            log.warn("Did not know how to read '" + definition + "'");
//        }


        for (final var operationType : schema.getOperationTypeDefinitions()) {

            final var targetType = operationType.getTypeName();
            final var targetDefinition = this.getObjectTypeDefinition(document, targetType);
            if (targetDefinition == null) {
                throw new IllegalArgumentException("There was no type found for '" + targetType + "'");
            }

            switch (operationType.getName()) {
                case "query":
                    this.readSchemaQuery(document, "queries", targetDefinition, spec, contracts);
                    break;
                case "mutation":

                    //final var mutationType = this.getObjectTypeDefinition(document, targetType);

                    this.readSchemaQuery(document, "mutations", targetDefinition, spec, contracts);


                    break;
                default:
                    log.warn("Did not know how to read operationType '" + operationType + "'");
                    break;
            }
        }
    }

    private void readSchemaQuery(Document document, String schemaType, ObjectTypeDefinition queryDefinition, JsonNode spec, Collection<Contract> contracts) {

        for (final var fieldDef : queryDefinition.getFieldDefinitions()) {

            final var queryMethodName = fieldDef.getName();
            final var responseType = fieldDef.getType();

            final var operationNode = spec.path(schemaType).path(queryMethodName);

            this.iterateArray(operationNode.path("x-contracts"), (operationContractNode) -> {

                final var yamlContract = this.readYamlContract(operationContractNode);

                yamlContract.request = new YamlContract.Request();
                yamlContract.request.method = "POST"; // StringUtils.upperCase(operationKey, Locale.US);

                yamlContract.response = new YamlContract.Response();

                final var contractId = operationContractNode.path("contractId").asText(null);

                final var responseBaseType = this.getTypeName(responseType);
                final var responseDefinition = this.getObjectTypeDefinition(document, responseBaseType);

                for (final var inputDefinition : fieldDef.getInputValueDefinitions()) {

                    final var inputType = inputDefinition.getType();
                    final var inputBaseType = this.getTypeName(inputType);
                }

                this.addOperationRequestContract(operationContractNode, yamlContract);

                this.iterateArray(operationContractNode.path("parameters"), (parameterNode) -> {
                    this.addOperationParameterContract(parameterNode, parameterNode, yamlContract);
                });

                this.addRequestBodyContract(operationContractNode.path("request"), yamlContract);
                this.addResponsesContract(operationContractNode.path("response"), "200", yamlContract);

                if (StringUtils.isBlank(yamlContract.request.url)) {
                    yamlContract.request.url = this.url;
                }

                Iterables.addAll(contracts, this.convertYamlContractToContract(yamlContract));
            });
        }
    }

    private TypeName getTypeName(Type<?> type) {

        if (type instanceof TypeName) {
            return ((TypeName) type);
        } else if (type instanceof NonNullType) {
            return this.getTypeName(((NonNullType) type).getType());
        } else if (type instanceof ListType) {
            return this.getTypeName(((ListType) type).getType());
        }

        return null;
    }

    private ObjectTypeDefinition getObjectTypeDefinition(Document document, TypeName typeName) {

        for (final var definition : document.getDefinitions()) {
            if (definition instanceof ObjectTypeDefinition) {
                final var objectType = (ObjectTypeDefinition) definition;
                if (StringUtils.equals(objectType.getName(), typeName.getName())) {
                    return (ObjectTypeDefinition) definition;
                }
            }
        }

        return null;
    }

//    private <T extends Definition> T getDefinition(Document document, TypeName typeName) {
//
//        for (final var definition : document.getDefinitions()) {
//
//            if (definition instanceof ObjectTypeDefinition) {
//
//                final var objectType = (ObjectTypeDefinition) definition;
//
//                if (StringUtils.equals(objectType.getName(), typeName.getName())) {
//                    return (T) definition;
//                }
//            }
//        }
//
//        return null;
//    }

    @Override
    public Collection<Document> convertTo(Collection<Contract> contract) {
        return null;
    }
}
