package io.github.doantrantuandat.example.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.crnk.core.resource.annotations.*;
import lombok.Data;

/**
 * Local stand-in for ordering-service's real "order" resource, needed only to give crnk-client a Java
 * type to deserialize remote JSON:API "order" documents into - matched by {@code type}, not by sharing a
 * compiled class with ordering-service. The "account"/"accountId" fields are needed too, even though
 * nothing here ever reads them back: OrderLinkerModule filters by "account.id", and crnk validates a
 * FilterSpec's path against this LOCAL shadow class's own resource metadata before ever sending the
 * request - a resolvable "account.id" path requires this declared relation to exist here, not just on
 * ordering-service's real entity.
 */
@Data
@JsonApiResource(type = "order")
public class Order {
    @JsonApiId
    private Long id;

    @JsonProperty
    private String orderNumber;

    @JsonApiRelationId
    private Long accountId;

    @JsonApiRelation(serialize = SerializeType.ONLY_ID)
    private Account account;
}
