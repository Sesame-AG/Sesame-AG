package io.github.aoguai.sesameag.model.modelFieldExt

import io.github.aoguai.sesameag.util.TimeTriggerParseOptions

class TimeTriggerModelField(
    code: String,
    name: String,
    value: String,
    parseOptions: TimeTriggerParseOptions = TimeTriggerParseOptions()
) : TimeRuleModelField(
    code = code,
    name = name,
    value = value,
    fieldType = TimeFieldType.TIME_TRIGGER,
    meta = TimeFieldMeta(
        allowDisable = true,
        allowSeconds = true,
        allowBlockedWindows = parseOptions.allowBlockedWindows,
        allowCheckpoints = parseOptions.allowCheckpoints,
        allowWindows = parseOptions.allowWindows,
        precision = "second",
        displayMode = "trigger"
    ),
    parseOptions = parseOptions
)
