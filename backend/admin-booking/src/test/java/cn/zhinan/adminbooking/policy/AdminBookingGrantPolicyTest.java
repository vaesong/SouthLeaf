package cn.zhinan.adminbooking.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import org.junit.jupiter.api.Test;

/** The grant policy is a closed list: no substring, prefix or probe matching. */
class AdminBookingGrantPolicyTest {

  @Test
  void theWriterHoldsEveryOwnedTable() {
    assertThat(AdminBookingGrantPolicy.requiredWriterGrants())
        .hasSameSizeAs(AdminBookingGrantPolicy.OWNED_TABLES)
        .contains("booking_order_snapshot:ALL", "booking_slot_occupancy:ALL");
  }

  @Test
  void theReaderOnlyHoldsSelect() {
    assertThat(AdminBookingGrantPolicy.requiredReaderGrants())
        .contains("booking_customer:SELECT")
        .noneMatch(grant -> grant.endsWith(":ALL"));
  }

  @Test
  void aTableOutsideTheOwnedListIsRejected() {
    assertThatThrownBy(
            () ->
                AdminBookingGrantPolicy.requireGrant(
                    "admin_work", AdminBookingGrantPolicy.BOOKING_WRITER, true))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure()).isEqualTo(BookingFailure.FORBIDDEN));
  }

  @Test
  void aTableNameThatOnlySharesAPrefixIsNotOwned() {
    // "booking_order_snapshot_x" must not be covered by the grant meant for "booking_order".
    assertThatThrownBy(
            () ->
                AdminBookingGrantPolicy.requireGrant(
                    "booking_order_snapshot_x", AdminBookingGrantPolicy.BOOKING_WRITER, true))
        .isInstanceOf(BookingException.class);
    assertThatThrownBy(
            () ->
                AdminBookingGrantPolicy.requireGrant(
                    "booking_orderx", AdminBookingGrantPolicy.BOOKING_WRITER, true))
        .isInstanceOf(BookingException.class);
  }

  @Test
  void theSnapshotTableNeedsItsOwnGrant() {
    // The order table and the snapshot table are separate entries in the closed list.
    assertThat(AdminBookingGrantPolicy.OWNED_TABLES)
        .contains("booking_order", "booking_order_snapshot");
    assertThat(AdminBookingGrantPolicy.requiredWriterGrants())
        .contains("booking_order:ALL", "booking_order_snapshot:ALL");
  }

  @Test
  void onlyTheDeclaredOwnerMayWrite() {
    assertThatThrownBy(
            () ->
                AdminBookingGrantPolicy.requireGrant(
                    "booking_customer", AdminBookingGrantPolicy.BOOKING_READER, true))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex -> assertThat(((BookingException) ex).reason()).isEqualTo("GRANTEE_NOT_OWNER"));
  }

  @Test
  void anUnknownGranteeIsRejectedEvenForReading() {
    assertThatThrownBy(
            () ->
                AdminBookingGrantPolicy.requireGrant("booking_customer", "some_other_role", false))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex -> assertThat(((BookingException) ex).reason()).isEqualTo("GRANTEE_NOT_ALLOWED"));
  }

  @Test
  void thePublicReaderHoldsNoBookingTable() {
    for (String table : AdminBookingGrantPolicy.OWNED_TABLES) {
      assertThatThrownBy(
              () ->
                  AdminBookingGrantPolicy.requireGrant(
                      table, AdminBookingGrantPolicy.PUBLIC_READER, false))
          .isInstanceOf(BookingException.class);
    }
  }

  @Test
  void theWriterMayReadTheContentTablesButTheBookingSliceNeverWritesThem() {
    assertThat(AdminBookingGrantPolicy.isReadableByWriter("admin_work")).isTrue();
    assertThat(AdminBookingGrantPolicy.isOwnedTable("admin_work")).isFalse();
  }
}
