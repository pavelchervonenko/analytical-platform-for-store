package com.storeanalytics.product.service;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.product.exception.ProductIdentityConflictException;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductDetails;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.repository.ProductRepository;
import com.storeanalytics.sync.model.SourceSystem;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LiveSkladProductIdentityResolver {

    private static final long ADVISORY_LOCK_SEED = 0L;

    private final ProductRepository productRepository;
    private final EntityManager entityManager;

    public LiveSkladProductIdentityResolver(
            ProductRepository productRepository,
            EntityManager entityManager
    ) {
        this.productRepository = productRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductIdentityResolution resolveObservedProduct(
            IntegrationConnection connection,
            String externalId,
            ProductDetails details
    ) {
        requireLiveSkladConnection(connection);
        requireText(externalId, "externalId");
        if (details == null
                || details.sourceKind() == ProductSourceKind.UNKNOWN) {
            throw new IllegalArgumentException(
                    "Observed LiveSklad product details must have a final source kind"
            );
        }
        lockIdentities(connection.getId(), observedIdentifiers(
                externalId,
                details.code()
        ));

        Product exact = productRepository.findByConnectionIdAndExternalId(
                connection.getId(),
                externalId
        ).orElse(null);
        if (exact != null) {
            return resolution(exact, exact.updateFromLiveSklad(details));
        }

        if (StringUtils.hasText(details.code())) {
            List<Product> codeMatches = productRepository
                    .findAllByConnectionIdAndCode(
                            connection.getId(),
                            details.code()
                    );
            if (codeMatches.size() > 1) {
                throw conflict(
                        "LiveSklad product code resolves to multiple products"
                );
            }
            if (!codeMatches.isEmpty()) {
                Product candidate = codeMatches.getFirst();
                if (!candidate.isProvisionalCatalogIdentity()) {
                    throw conflict(
                            "LiveSklad product code conflicts with an existing product"
                    );
                }
                candidate.claimLiveSkladIdentity(externalId, details);
                return new ProductIdentityResolution(
                        candidate,
                        ResolutionKind.UPDATED
                );
            }
        }

        Product created = productRepository.save(Product.fromLiveSklad(
                connection,
                externalId,
                details
        ));
        return new ProductIdentityResolution(created, ResolutionKind.CREATED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CatalogResolution resolveCatalogReferences(
            IntegrationConnection connection,
            Map<String, String> productNamesByIdentifier
    ) {
        requireLiveSkladConnection(connection);
        if (productNamesByIdentifier.isEmpty()) {
            return new CatalogResolution(Map.of(), 0);
        }
        productNamesByIdentifier.keySet().forEach(identifier ->
                requireText(identifier, "sourceIdentifier")
        );
        lockIdentities(connection.getId(), productNamesByIdentifier.keySet());

        Set<String> identifiers = Set.copyOf(productNamesByIdentifier.keySet());
        List<Product> candidates = findCatalogCandidates(
                connection.getId(),
                identifiers
        );
        Map<String, Product> resolved = new HashMap<>();
        for (Product candidate : candidates) {
            if (identifiers.contains(candidate.getExternalId())) {
                putResolved(resolved, candidate.getExternalId(), candidate);
            }
            if (identifiers.contains(candidate.getCode())) {
                putResolved(resolved, candidate.getCode(), candidate);
            }
        }

        List<Product> created = new ArrayList<>();
        for (Map.Entry<String, String> reference
                : productNamesByIdentifier.entrySet()) {
            if (resolved.containsKey(reference.getKey())) {
                continue;
            }
            Product product = Product.fromLiveSklad(
                    connection,
                    reference.getKey(),
                    new ProductDetails(
                            null,
                            reference.getKey(),
                            null,
                            reference.getValue(),
                            ProductSourceKind.UNKNOWN,
                            null
                    )
            );
            resolved.put(reference.getKey(), product);
            created.add(product);
        }
        if (!created.isEmpty()) {
            productRepository.saveAllAndFlush(created);
        }
        return new CatalogResolution(
                Map.copyOf(resolved),
                created.size()
        );
    }

    private List<Product> findCatalogCandidates(
            UUID connectionId,
            Set<String> identifiers
    ) {
        return entityManager.createQuery(
                        """
                        SELECT product
                        FROM Product product
                        WHERE product.connection.id = :connectionId
                          AND (product.externalId IN :identifiers
                               OR product.code IN :identifiers)
                        """,
                        Product.class
                )
                .setParameter("connectionId", connectionId)
                .setParameter("identifiers", identifiers)
                .getResultList();
    }

    private void putResolved(
            Map<String, Product> resolved,
            String identifier,
            Product candidate
    ) {
        Product previous = resolved.putIfAbsent(identifier, candidate);
        if (previous != null && !previous.getId().equals(candidate.getId())) {
            throw conflict(
                    "Product identifier is ambiguous within the integration connection"
            );
        }
    }

    private void lockIdentities(
            UUID connectionId,
            Collection<String> identifiers
    ) {
        Set<String> lockKeys = new TreeSet<>();
        for (String identifier : identifiers) {
            if (!StringUtils.hasText(identifier)) {
                continue;
            }
            lockKeys.add(lockKey(connectionId, "code", identifier));
            lockKeys.add(lockKey(connectionId, "external", identifier));
        }
        for (String lockKey : lockKeys) {
            entityManager.createNativeQuery(
                            "SELECT pg_advisory_xact_lock("
                                    + "hashtextextended(CAST(:lockKey AS text), "
                                    + ADVISORY_LOCK_SEED + "))"
                    )
                    .setParameter("lockKey", lockKey)
                    .getSingleResult();
        }
    }

    private Collection<String> observedIdentifiers(
            String externalId,
            String code
    ) {
        Set<String> identifiers = new LinkedHashSet<>();
        identifiers.add(externalId);
        if (StringUtils.hasText(code)) {
            identifiers.add(code);
        }
        return identifiers;
    }

    private String lockKey(UUID connectionId, String kind, String value) {
        return "livesklad-product:" + connectionId + ":" + kind + ":" + value;
    }

    private ProductIdentityResolution resolution(
            Product product,
            boolean changed
    ) {
        return new ProductIdentityResolution(
                product,
                changed ? ResolutionKind.UPDATED : ResolutionKind.UNCHANGED
        );
    }

    private void requireLiveSkladConnection(IntegrationConnection connection) {
        if (connection == null
                || connection.getSourceSystem() != SourceSystem.LIVESKLAD) {
            throw new IllegalArgumentException(
                    "A LiveSklad integration connection is required"
            );
        }
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private ProductIdentityConflictException conflict(String message) {
        return new ProductIdentityConflictException(message);
    }

    public record ProductIdentityResolution(
            Product product,
            ResolutionKind kind
    ) {
    }

    public record CatalogResolution(
            Map<String, Product> productsByIdentifier,
            int createdCount
    ) {
    }

    public enum ResolutionKind {
        CREATED,
        UPDATED,
        UNCHANGED
    }
}
