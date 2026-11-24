package com.privateinvest.crm.customfield;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** Values of custom fields to change, keyed by api name. Only the fields sent change; null clears one. */
public record ValuesUpdate(@NotNull Map<String, Object> values) {
}
