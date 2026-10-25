package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingModels.ContactPoint;
import cn.zhinan.adminbooking.domain.BookingModels.Customer;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkDecision;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkRequest;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerRelationship;
import cn.zhinan.adminbooking.domain.BookingModels.ExternalIdentity;
import cn.zhinan.adminbooking.domain.BookingModels.RelationshipType;
import cn.zhinan.adminbooking.port.BookingPorts.CustomerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Customer identity persistence. Raw contact values are stored in a protected column and are never
 * selected by the list queries; only masked values leave this adapter.
 */
public final class JdbcCustomerRepository implements CustomerRepository {
  private final JdbcConnectionProvider connections;

  public JdbcCustomerRepository(JdbcConnectionProvider connections) {
    this.connections = connections;
  }

  @Override
  public Optional<Customer> find(String customerId) {
    Customer customer =
        JdbcSupport.queryOne(
            connections,
            "SELECT id, status, display_name, language, created_at, version"
                + " FROM booking_customer WHERE id = ?",
            resultSet ->
                new Customer(
                    resultSet.getString("id"),
                    resultSet.getString("status"),
                    resultSet.getString("display_name"),
                    resultSet.getString("language"),
                    JdbcSupport.instant(resultSet, "created_at"),
                    resultSet.getInt("version")),
            customerId);
    return Optional.ofNullable(customer);
  }

  @Override
  public Customer create(String customerId, String displayName, String language, Instant now) {
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_customer"
            + " (id, status, display_name, language, created_at, updated_at, version)"
            + " VALUES (?, 'ACTIVE', ?, ?, ?, ?, 1)",
        customerId,
        displayName,
        language,
        now,
        now);
    return find(customerId)
        .orElseThrow(
            () -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "CUSTOMER_NOT_STORED"));
  }

  @Override
  public Optional<ExternalIdentity> findExternalIdentity(
      String provider, String appScope, String subjectFingerprint) {
    ExternalIdentity identity =
        JdbcSupport.queryOne(
            connections,
            "SELECT customer_id, provider, app_scope, subject_fingerprint, status, verified_at"
                + " FROM booking_external_identity WHERE provider = ? AND app_scope = ?"
                + " AND subject_fingerprint = ?",
            resultSet ->
                new ExternalIdentity(
                    resultSet.getString("customer_id"),
                    resultSet.getString("provider"),
                    resultSet.getString("app_scope"),
                    resultSet.getString("subject_fingerprint"),
                    resultSet.getString("status"),
                    JdbcSupport.instant(resultSet, "verified_at")),
            provider,
            appScope,
            subjectFingerprint);
    return Optional.ofNullable(identity);
  }

  @Override
  public ExternalIdentity createExternalIdentity(
      String customerId,
      String provider,
      String appScope,
      String subjectFingerprint,
      Instant verifiedAt,
      Instant now) {
    String status = verifiedAt == null ? "PENDING" : "VERIFIED";
    JdbcSupport.updateProtected(
        connections,
        "EXTERNAL_IDENTITY_CONFLICT",
        "INSERT INTO booking_external_identity"
            + " (id, customer_id, provider, app_scope, subject_fingerprint, status, verified_at,"
            + " created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
        cn.zhinan.adminbooking.domain.BookingRules.newId(),
        customerId,
        provider,
        appScope,
        subjectFingerprint,
        status,
        verifiedAt,
        now,
        now);
    return findExternalIdentity(provider, appScope, subjectFingerprint)
        .orElseThrow(
            () -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "IDENTITY_NOT_STORED"));
  }

  @Override
  public Optional<ContactPoint> findContactPoint(String contactPointId) {
    ContactPoint contact =
        JdbcSupport.queryOne(
            connections,
            "SELECT id, customer_id, type, value_masked, verification_status, created_at"
                + " FROM booking_contact_point WHERE id = ?",
            JdbcCustomerRepository::mapContactPoint,
            contactPointId);
    return Optional.ofNullable(contact);
  }

  @Override
  public ContactPoint createContactPoint(
      String contactPointId,
      String customerId,
      String type,
      byte[] encryptedValue,
      String maskedValue,
      String verificationStatus,
      Instant now) {
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_contact_point"
            + " (id, customer_id, type, value_cipher, value_masked, verification_status,"
            + " is_primary, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, 1)",
        contactPointId,
        customerId,
        type,
        encryptedValue,
        maskedValue,
        verificationStatus,
        now,
        now);
    return findContactPoint(contactPointId)
        .orElseThrow(
            () -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "CONTACT_NOT_STORED"));
  }

  @Override
  public List<ContactPoint> listContactPoints(String customerId) {
    return JdbcSupport.queryList(
        connections,
        "SELECT id, customer_id, type, value_masked, verification_status, created_at"
            + " FROM booking_contact_point WHERE customer_id = ? ORDER BY created_at ASC, id ASC",
        JdbcCustomerRepository::mapContactPoint,
        customerId);
  }

  @Override
  public Optional<CustomerRelationship> findRelationship(String relationshipId) {
    CustomerRelationship relationship =
        JdbcSupport.queryOne(
            connections,
            "SELECT id, customer_id, related_customer_id, relation_type, purpose,"
                + " verification_status, valid_from, valid_until, revoked FROM"
                + " booking_customer_relationship WHERE id = ?",
            JdbcCustomerRepository::mapRelationship,
            relationshipId);
    return Optional.ofNullable(relationship);
  }

  @Override
  public Optional<CustomerRelationship> findRelationship(
      String customerId, String relatedCustomerId) {
    CustomerRelationship relationship =
        JdbcSupport.queryOne(
            connections,
            "SELECT id, customer_id, related_customer_id, relation_type, purpose,"
                + " verification_status, valid_from, valid_until, revoked FROM"
                + " booking_customer_relationship WHERE customer_id = ? AND related_customer_id ="
                + " ?",
            JdbcCustomerRepository::mapRelationship,
            customerId,
            relatedCustomerId);
    return Optional.ofNullable(relationship);
  }

  @Override
  public CustomerRelationship approveRelationship(
      String relationshipId,
      String customerId,
      String relatedCustomerId,
      String relationType,
      String purpose,
      Instant validFrom,
      Instant validUntil,
      String actorId,
      Instant now) {
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_customer_relationship"
            + " (id, customer_id, related_customer_id, relation_type, purpose, verification_status,"
            + " valid_from, valid_until, revoked, created_at, updated_at, version)"
            + " VALUES (?, ?, ?, ?, ?, 'APPROVED', ?, ?, 0, ?, ?, 1)",
        relationshipId,
        customerId,
        relatedCustomerId,
        relationType,
        purpose,
        validFrom,
        validUntil,
        now,
        now);
    return findRelationship(relationshipId)
        .orElseThrow(
            () ->
                new BookingException(
                    BookingFailure.STORAGE_UNAVAILABLE, "RELATIONSHIP_NOT_STORED"));
  }

  @Override
  public Optional<CustomerLinkRequest> findLinkRequest(String linkRequestId) {
    CustomerLinkRequest request =
        JdbcSupport.queryOne(
            connections,
            "SELECT id, requester_customer_id, candidate_ref, method, status, expires_at,"
                + " reviewed_at FROM booking_customer_link_request WHERE id = ?",
            resultSet ->
                new CustomerLinkRequest(
                    resultSet.getString("id"),
                    resultSet.getString("requester_customer_id"),
                    resultSet.getString("candidate_ref"),
                    resultSet.getString("method"),
                    resultSet.getString("status"),
                    JdbcSupport.instant(resultSet, "expires_at"),
                    JdbcSupport.instant(resultSet, "reviewed_at")),
            linkRequestId);
    return Optional.ofNullable(request);
  }

  @Override
  public CustomerLinkRequest createLinkRequest(
      String linkRequestId,
      String requesterCustomerId,
      String candidateRef,
      String method,
      Instant expiresAt,
      Instant now) {
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_customer_link_request"
            + " (id, requester_customer_id, candidate_ref, method, status, expires_at, created_at)"
            + " VALUES (?, ?, ?, ?, 'PENDING', ?, ?)",
        linkRequestId,
        requesterCustomerId,
        candidateRef,
        method,
        expiresAt,
        now);
    return findLinkRequest(linkRequestId)
        .orElseThrow(
            () ->
                new BookingException(
                    BookingFailure.STORAGE_UNAVAILABLE, "LINK_REQUEST_NOT_STORED"));
  }

  @Override
  public CustomerLinkDecision decideLinkRequest(
      String linkRequestId, boolean approve, String actorId, Instant now) {
    String status = approve ? "APPROVED" : "REJECTED";
    int updated =
        JdbcSupport.update(
            connections,
            "UPDATE booking_customer_link_request SET status = ?, reviewed_at = ?, reviewed_by = ?"
                + " WHERE id = ? AND status = 'PENDING'",
            status,
            now,
            actorId,
            linkRequestId);
    if (updated == 0)
      throw new BookingException(BookingFailure.CONFLICT, "LINK_REQUEST_ALREADY_DECIDED");
    if (!approve) return new CustomerLinkDecision(linkRequestId, status, Optional.empty());
    CustomerLinkRequest request =
        findLinkRequest(linkRequestId)
            .orElseThrow(
                () ->
                    new BookingException(
                        BookingFailure.STORAGE_UNAVAILABLE, "LINK_REQUEST_NOT_STORED"));
    String relationshipId = cn.zhinan.adminbooking.domain.BookingRules.newId();
    approveRelationship(
        relationshipId,
        request.requesterCustomerId(),
        request.candidateRef(),
        RelationshipType.OTHER.name(),
        "BOOKING",
        now,
        null,
        actorId,
        now);
    return new CustomerLinkDecision(linkRequestId, status, Optional.of(relationshipId));
  }

  @Override
  public List<CustomerRelationship> listRelationships(String customerId) {
    return JdbcSupport.queryList(
        connections,
        "SELECT id, customer_id, related_customer_id, relation_type, purpose, verification_status,"
            + " valid_from, valid_until, revoked FROM booking_customer_relationship"
            + " WHERE customer_id = ? ORDER BY created_at ASC, id ASC",
        JdbcCustomerRepository::mapRelationship,
        customerId);
  }

  private static ContactPoint mapContactPoint(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    return new ContactPoint(
        resultSet.getString("id"),
        resultSet.getString("customer_id"),
        resultSet.getString("type"),
        resultSet.getString("value_masked"),
        resultSet.getString("verification_status"),
        JdbcSupport.instant(resultSet, "created_at"));
  }

  private static CustomerRelationship mapRelationship(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    return new CustomerRelationship(
        resultSet.getString("id"),
        resultSet.getString("customer_id"),
        resultSet.getString("related_customer_id"),
        RelationshipType.valueOf(resultSet.getString("relation_type")),
        resultSet.getString("purpose"),
        resultSet.getString("verification_status"),
        JdbcSupport.instant(resultSet, "valid_from"),
        JdbcSupport.instant(resultSet, "valid_until"),
        resultSet.getBoolean("revoked"));
  }
}
