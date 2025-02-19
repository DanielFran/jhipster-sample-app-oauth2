package io.github.jhipster.sample.security.oauth2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jhipster.sample.security.SecurityUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Claim converter to add custom claims by retrieving the user from the userinfo endpoint.
 */
public class CustomClaimConverter implements Converter<Map<String, Object>, Map<String, Object>> {

    private final BearerTokenResolver bearerTokenResolver = new DefaultBearerTokenResolver();

    private final MappedJwtClaimSetConverter delegate = MappedJwtClaimSetConverter.withDefaults(Collections.emptyMap());

    private final RestTemplate restTemplate;

    private final ClientRegistration registration;

    private final Map<String, ObjectNode> users = new ConcurrentHashMap<>();

    public CustomClaimConverter(ClientRegistration registration, RestTemplate restTemplate) {
        this.registration = registration;
        this.restTemplate = restTemplate;
    }

    public Map<String, Object> convert(Map<String, Object> claims) {
        Map<String, Object> convertedClaims = this.delegate.convert(claims);
        if (
            RequestContextHolder.getRequestAttributes() != null &&
            ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()) != null
        ) {
            // Retrieve and set the token
            String token = bearerTokenResolver.resolve(
                ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest()
            );
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", buildBearer(token));

            // Retrieve user infos from OAuth provider if not already loaded
            ObjectNode user = users.computeIfAbsent(
                claims.get("sub").toString(),
                s -> {
                    ResponseEntity<ObjectNode> userInfo = restTemplate.exchange(
                        registration.getProviderDetails().getUserInfoEndpoint().getUri(),
                        HttpMethod.GET,
                        new HttpEntity<String>(headers),
                        ObjectNode.class
                    );
                    return userInfo.getBody();
                }
            );

            // Add custom claims
            if (user != null) {
                convertedClaims.put("preferred_username", user.get("preferred_username").asText());
                if (user.has("given_name")) {
                    convertedClaims.put("given_name", user.get("given_name").asText());
                }
                if (user.has("family_name")) {
                    convertedClaims.put("family_name", user.get("family_name").asText());
                }
                if (user.has("email")) {
                    convertedClaims.put("email", user.get("email").asText());
                }
                // Allow full name in a name claim - happens with Auth0
                if (user.has("name")) {
                    String[] name = user.get("name").asText().split("\\s+");
                    if (name.length > 0) {
                        convertedClaims.put("given_name", name[0]);
                        convertedClaims.put("family_name", String.join(" ", Arrays.copyOfRange(name, 1, name.length)));
                    }
                }
                if (user.has("groups")) {
                    List<String> groups = StreamSupport
                        .stream(user.get("groups").spliterator(), false)
                        .map(JsonNode::asText)
                        .collect(Collectors.toList());
                    convertedClaims.put("groups", groups);
                }
                if (user.has(SecurityUtils.CLAIMS_NAMESPACE + "roles")) {
                    List<String> roles = StreamSupport
                        .stream(user.get(SecurityUtils.CLAIMS_NAMESPACE + "roles").spliterator(), false)
                        .map(JsonNode::asText)
                        .collect(Collectors.toList());
                    convertedClaims.put("roles", roles);
                }
            }
        }
        return convertedClaims;
    }

    private String buildBearer(String token) {
        return "Bearer " + token;
    }
}
