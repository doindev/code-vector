package io.doindev.cvector.rest;

/**
 * Fired after {@code .cvector/settings.json} is written. Beans that cache config-derived state
 * can listen and refresh; beans that bind ports / transports can surface a "restart required"
 * banner. Source identifies the writer (e.g. {@code "rest"} for {@code PUT /api/settings},
 * {@code "cli"} for command-line edits if/when the CLI fires it via REST hook).
 */
public record SettingsChangedEvent(String source) {}
