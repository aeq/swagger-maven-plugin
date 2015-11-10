package com.github.kongchen.swagger.docgen.reader;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.AuthorizationScope;
import io.swagger.converter.ModelConverters;
import io.swagger.jaxrs.ext.SwaggerExtension;
import io.swagger.jaxrs.ext.SwaggerExtensions;
import io.swagger.models.Model;
import io.swagger.models.Operation;
import io.swagger.models.Response;
import io.swagger.models.SecurityRequirement;
import io.swagger.models.SecurityScope;
import io.swagger.models.Swagger;
import io.swagger.models.Tag;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.util.Json;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.Consumes;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.kongchen.swagger.docgen.LogAdapter;

public class JaxrsReader extends AbstractReader implements ClassSwaggerReader {
    Logger LOGGER = LoggerFactory.getLogger(JaxrsReader.class);

    static ObjectMapper m = Json.mapper();

    public JaxrsReader(Swagger swagger, LogAdapter LOG) {
        super(swagger, LOG);

    }

    @Override
    public Swagger read(Set<Class<?>> classes) {
        for (Class cls : classes)
            read(cls);
        return swagger;
    }

    public Swagger getSwagger() {
        return this.swagger;
    }
    
    /**
     * Sort the methods, put the get multiple methods (for example, getAll) to the end. 
     * @param methods
     * @return
     */
    public Method[] sortMethod(Method[] methods) {
        if (methods == null) {
            return null;
        }
        List<Method> allMethods = new ArrayList<Method>();
        List<Method> postMethods = new ArrayList<Method>();
        for (Method m : methods) {
            ApiOperation apiOperation = m.getAnnotation(ApiOperation.class);
            POST post = m.getAnnotation(POST.class);
            if ((apiOperation != null && "POST".equalsIgnoreCase(apiOperation.httpMethod())) || post != null) {
                postMethods.add(m);
            } else {
                allMethods.add(m);
            }
        }
        
        for (Method m : postMethods) {
            allMethods.add(0, m);
        }
        return allMethods.toArray(new Method[allMethods.size()]);
    }


    public Swagger read(Class cls) {
        return read(cls, "", null, false, new String[0], new String[0], new HashMap<String, Tag>(), new ArrayList<Parameter>());
    }

    protected Swagger read(Class<?> cls, String parentPath, String parentMethod, boolean readHidden, String[] parentConsumes, String[] parentProduces, Map<String, Tag> parentTags, List<Parameter> parentParameters) {
        if (swagger == null)
            swagger = new Swagger();
        Api api = cls.getAnnotation(Api.class);
        Map<String, SecurityScope> globalScopes = new HashMap<String, SecurityScope>();

        javax.ws.rs.Path apiPath = cls.getAnnotation(javax.ws.rs.Path.class);

        // only read if allowing hidden apis OR api is not marked as hidden
        if (!canReadApi(readHidden, api)) {
            return swagger;
        }

        Map<String, Tag> tags = updateTagsForApi(parentTags, api);

        List<SecurityRequirement> securities = getSecurityRequirements(api);

        // merge consumes, produces

        // look for method-level annotated properties

        // handle subresources by looking at return type

        // parse the method
        Method methods[] = sortMethod(cls.getMethods());
        for (Method method : methods) {

            ApiOperation apiOperation = method.getAnnotation(ApiOperation.class);
            if (apiOperation == null || apiOperation.hidden()) {
                continue;
            }
            javax.ws.rs.Path methodPath = method.getAnnotation(javax.ws.rs.Path.class);

            String operationPath = getPath(apiPath, methodPath, parentPath);
            if (operationPath != null && apiOperation != null) {
                Map<String, String> regexMap = new HashMap<String, String>();
                operationPath = parseOperationPath(operationPath, regexMap);

                String httpMethod = extractOperationMethod(apiOperation, method, SwaggerExtensions.chain());

                Operation operation = parseMethod(method);

                updateOperationParameters(parentParameters, regexMap, operation);

                updateOperationProtocols(apiOperation, operation);

                String[] apiConsumes = new String[0];
                String[] apiProduces = new String[0];

                Annotation annotation = cls.getAnnotation(Consumes.class);
                if (annotation != null)
                    apiConsumes = ((Consumes) annotation).value();
                annotation = cls.getAnnotation(Produces.class);
                if (annotation != null)
                    apiProduces = ((Produces) annotation).value();

                apiConsumes = updateOperationConsumes(parentConsumes, apiConsumes, operation);
                apiProduces = updateOperationProduces(parentProduces, apiProduces, operation);

                handleSubResource(apiConsumes, httpMethod, apiProduces, tags, method, operationPath, operation);

                // can't continue without a valid http method
                httpMethod = httpMethod == null ? parentMethod : httpMethod;
                updateTagsForOperation(operation, apiOperation);
                updateOperation(apiConsumes, apiProduces, tags, securities, operation);
                updatePath(operationPath, httpMethod, operation);

            }
        }

        return swagger;
    }


    private void handleSubResource(String[] apiConsumes, String httpMethod, String[] apiProduces, Map<String, Tag> tags, Method method, String operationPath, Operation operation) {
        if (isSubResource(method)) {
            Type t = method.getGenericReturnType();
            Class<?> responseClass = method.getReturnType();
            Swagger subSwagger = read(responseClass, operationPath, httpMethod, true, apiConsumes, apiProduces, tags, operation.getParameters());
        }
    }

    protected boolean isSubResource(Method method) {
        Type t = method.getGenericReturnType();
        Class<?> responseClass = method.getReturnType();
        if (responseClass != null && responseClass.getAnnotation(Api.class) != null) {
            return true;
        }
        return false;
    }

    String getPath(javax.ws.rs.Path classLevelPath, javax.ws.rs.Path methodLevelPath, String parentPath) {
        if (classLevelPath == null && methodLevelPath == null)
            return null;
        StringBuilder b = new StringBuilder();
        if (parentPath != null && !"".equals(parentPath) && !"/".equals(parentPath)) {
            if (!parentPath.startsWith("/"))
                parentPath = "/" + parentPath;
            if (parentPath.endsWith("/"))
                parentPath = parentPath.substring(0, parentPath.length() - 1);

            b.append(parentPath);
        }
        if (classLevelPath != null) {
            b.append(classLevelPath.value());
        }
        if (methodLevelPath != null && !"/".equals(methodLevelPath.value())) {
            String methodPath = methodLevelPath.value();
            if (!methodPath.startsWith("/") && !b.toString().endsWith("/")) {
                b.append("/");
            }
            if (methodPath.endsWith("/")) {
                methodPath = methodPath.substring(0, methodPath.length() - 1);
            }
            b.append(methodPath);
        }
        String output = b.toString();
        if (!output.startsWith("/"))
            output = "/" + output;

        return output;
    }


    public Operation parseMethod(Method method) {
        Operation operation = new Operation();

        ApiOperation apiOperation = (ApiOperation) method.getAnnotation(ApiOperation.class);


        String operationId = method.getName();
        String responseContainer = null;

        Class<?> responseClass = null;
        Map<String, Property> defaultResponseHeaders = new HashMap<String, Property>();

        if (apiOperation != null) {
            if (apiOperation.hidden())
                return null;
            if (!"".equals(apiOperation.nickname()))
                operationId = apiOperation.nickname();

            defaultResponseHeaders = parseResponseHeaders(apiOperation.responseHeaders());

            operation
                    .summary(apiOperation.value())
                    .description(apiOperation.notes());

            if (apiOperation.response() != null && !Void.class.equals(apiOperation.response()))
                responseClass = apiOperation.response();
            if (!"".equals(apiOperation.responseContainer()))
                responseContainer = apiOperation.responseContainer();
            if (apiOperation.authorizations() != null) {
                List<SecurityRequirement> securities = new ArrayList<SecurityRequirement>();
                for (Authorization auth : apiOperation.authorizations()) {
                    if (auth.value() != null && !"".equals(auth.value())) {
                        SecurityRequirement security = new SecurityRequirement();
                        security.setName(auth.value());
                        AuthorizationScope[] scopes = auth.scopes();
                        for (AuthorizationScope scope : scopes) {
                            if (scope.scope() != null && !"".equals(scope.scope())) {
                                security.addScope(scope.scope());
                            }
                        }
                        securities.add(security);
                    }
                }
                if (securities.size() > 0) {
                    for (SecurityRequirement sec : securities)
                        operation.security(sec);
                }
            }
        }

        if (responseClass == null) {
            // pick out response from method declaration
            LOGGER.debug("picking up response class from method " + method);
            Type t = method.getGenericReturnType();
            responseClass = method.getReturnType();
            if (!responseClass.equals(java.lang.Void.class) && !"void".equals(responseClass.toString()) && responseClass.getAnnotation(Api.class) == null) {
                LOGGER.debug("reading model " + responseClass);
                Map<String, Model> models = ModelConverters.getInstance().readAll(t);
            }
        }
        if (responseClass != null
                && !responseClass.equals(java.lang.Void.class)
                && !responseClass.equals(javax.ws.rs.core.Response.class)
                && responseClass.getAnnotation(Api.class) == null) {
            if (isPrimitive(responseClass)) {
                Property responseProperty = null;
                Property property = ModelConverters.getInstance().readAsProperty(responseClass);
                if (property != null) {
                    if ("list".equalsIgnoreCase(responseContainer))
                        responseProperty = new ArrayProperty(property);
                    else if ("map".equalsIgnoreCase(responseContainer))
                        responseProperty = new MapProperty(property);
                    else
                        responseProperty = property;
                    operation.response(200, new Response()
                            .description("successful operation")
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                }
            } else if (!responseClass.equals(java.lang.Void.class) && !"void".equals(responseClass.toString())) {
                Map<String, Model> models = ModelConverters.getInstance().read(responseClass);
                if (models.size() == 0) {
                    Property p = ModelConverters.getInstance().readAsProperty(responseClass);
                    operation.response(200, new Response()
                            .description("successful operation")
                            .schema(p)
                            .headers(defaultResponseHeaders));
                }
                for (String key : models.keySet()) {
                    Property responseProperty = null;

                    if ("list".equalsIgnoreCase(responseContainer))
                        responseProperty = new ArrayProperty(new RefProperty().asDefault(key));
                    else if ("map".equalsIgnoreCase(responseContainer))
                        responseProperty = new MapProperty(new RefProperty().asDefault(key));
                    else
                        responseProperty = new RefProperty().asDefault(key);
                    operation.response(200, new Response()
                            .description("successful operation")
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                    swagger.model(key, models.get(key));
                }
                models = ModelConverters.getInstance().readAll(responseClass);
                for (String key : models.keySet()) {
                    swagger.model(key, models.get(key));
                }
            }
        }

        operation.operationId(operationId);

        Annotation annotation;
        annotation = method.getAnnotation(Consumes.class);
        if (annotation != null) {
            String[] apiConsumes = ((Consumes) annotation).value();
            for (String mediaType : apiConsumes)
                operation.consumes(mediaType);
        }

        annotation = method.getAnnotation(Produces.class);
        if (annotation != null) {
            String[] apiProduces = ((Produces) annotation).value();
            for (String mediaType : apiProduces)
                operation.produces(mediaType);
        }

        List<ApiResponse> apiResponses = new ArrayList<ApiResponse>();
        ApiResponses responseAnnotation = method.getAnnotation(ApiResponses.class);
        if (responseAnnotation != null) {
            updateApiResponse(operation, responseAnnotation);
        }
        boolean isDeprecated = false;
        annotation = method.getAnnotation(Deprecated.class);
        if (annotation != null)
            isDeprecated = true;

        boolean hidden = false;
        if (apiOperation != null)
            hidden = apiOperation.hidden();

        // process parameters
        Class[] parameterTypes = method.getParameterTypes();
        Type[] genericParameterTypes = method.getGenericParameterTypes();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        
        // paramTypes = method.getParameterTypes
        // genericParamTypes = method.getGenericParameterTypes
        for (int i = 0; i < parameterTypes.length; i++) {
            Type type = genericParameterTypes[i];
            List<Annotation> annotations = Arrays.asList(paramAnnotations[i]);
            List<Parameter> parameters = getParameters(type, annotations);

            for (Parameter parameter : parameters) {
                operation.parameter(parameter);
            }
        }
        if (operation.getResponses() == null) {
            operation.defaultResponse(new Response().description("successful operation"));
        }
        
        // Process @ApiImplicitParams
        this.readImplicitParameters(method, operation);
        
        return operation;
    }


    public String extractOperationMethod(ApiOperation apiOperation, Method method, Iterator<SwaggerExtension> chain) {
        if (apiOperation.httpMethod() != null && !"".equals(apiOperation.httpMethod()))
            return apiOperation.httpMethod().toLowerCase();
        else if (method.getAnnotation(javax.ws.rs.GET.class) != null)
            return "get";
        else if (method.getAnnotation(javax.ws.rs.PUT.class) != null)
            return "put";
        else if (method.getAnnotation(javax.ws.rs.POST.class) != null)
            return "post";
        else if (method.getAnnotation(javax.ws.rs.DELETE.class) != null)
            return "delete";
        else if (method.getAnnotation(javax.ws.rs.OPTIONS.class) != null)
            return "options";
        else if (method.getAnnotation(javax.ws.rs.HEAD.class) != null)
            return "head";
        else if (method.getAnnotation(io.swagger.jaxrs.PATCH.class) != null)
            return "patch";
        else {
            // check for custom HTTP Method annotations
            Annotation[] declaredAnnotations = method.getDeclaredAnnotations();
            for (Annotation declaredAnnotation : declaredAnnotations) {
                Annotation[] innerAnnotations = declaredAnnotation.annotationType().getAnnotations();
                for (Annotation innerAnnotation : innerAnnotations) {
                    if (innerAnnotation instanceof HttpMethod){
                        HttpMethod httpMethod = (HttpMethod) innerAnnotation;
                        return httpMethod.value().toLowerCase();
                    }
                }
            }

            if (chain.hasNext()) {
                return chain.next().extractOperationMethod(apiOperation, method, chain);
            }
        }

        return null;
    }


}
