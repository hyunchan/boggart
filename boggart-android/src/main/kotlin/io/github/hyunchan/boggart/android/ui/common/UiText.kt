package io.github.hyunchan.boggart.android.ui.common
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.Provenance
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.SequenceEnd
import io.github.hyunchan.boggart.android.R

/** 화면 문구를 리소스에서 고르는 곳. 순수 함수는 enum만 돌려주고 언어별 문구는 여기서 정한다. */

@StringRes
internal fun DraftError.messageRes(): Int = when (this) {
    DraftError.NoOriginalBody -> R.string.boggart_error_no_original_body
    DraftError.OriginalNotJson -> R.string.boggart_error_original_not_json
    DraftError.EditedNotJson -> R.string.boggart_error_edited_not_json
    DraftError.NoChanges -> R.string.boggart_error_no_changes
    DraftError.PatchNotJson -> R.string.boggart_error_patch_not_json
    DraftError.OpsNotJsonPatch -> R.string.boggart_error_ops_not_json_patch
    DraftError.NoOps -> R.string.boggart_error_no_ops
    DraftError.NoValueChanges -> R.string.boggart_error_no_value_changes
    DraftError.ValuesNotJson -> R.string.boggart_error_values_not_json
    DraftError.CannotApply -> R.string.boggart_error_cannot_apply
}

@Composable
internal fun RuleOrigin.label(): String = stringResource(
    when (this) {
        RuleOrigin.Temporary -> R.string.boggart_section_temporary
        RuleOrigin.Saved -> R.string.boggart_section_saved
        RuleOrigin.Preset -> R.string.boggart_section_preset
    },
)

@Composable
internal fun ValueType.label(): String = stringResource(
    when (this) {
        ValueType.Text -> R.string.boggart_type_text
        ValueType.Number -> R.string.boggart_type_number
        ValueType.Bool -> R.string.boggart_type_bool
        ValueType.Null -> R.string.boggart_type_null
        ValueType.Raw -> R.string.boggart_type_raw
    },
)

@Composable
internal fun failKindLabel(kind: FailKind): String = stringResource(
    when (kind) {
        FailKind.Timeout -> R.string.boggart_fail_timeout
        FailKind.ConnectionReset -> R.string.boggart_fail_connection_reset
        FailKind.UnknownHost -> R.string.boggart_fail_unknown_host
        is FailKind.Custom -> R.string.boggart_fail_custom
    },
)

@Composable
internal fun overrideLabel(override: Override): String = when (override) {
    is Override.Replace -> stringResource(R.string.boggart_override_replace, override.status)
    is Override.Patch -> stringResource(R.string.boggart_override_manual)
    is Override.JsonPatch -> pluralStringResource(R.plurals.boggart_override_values, override.ops.size, override.ops.size)
    is Override.Fail -> stringResource(R.string.boggart_override_fail, failKindLabel(override.kind))
    is Override.Sequence -> pluralStringResource(R.plurals.boggart_override_sequence, override.steps.size, override.steps.size)
} + if (override.delayMs > 0) " +${override.delayMs}ms" else ""

@Composable
internal fun SequenceEnd.label(): String = stringResource(
    when (this) {
        SequenceEnd.Last -> R.string.boggart_sequence_then_last
        SequenceEnd.Loop -> R.string.boggart_sequence_then_loop
        SequenceEnd.Original -> R.string.boggart_sequence_then_original
    },
)

@Composable
internal fun matcherLabel(rule: Rule): String = with(rule.matcher) {
    listOfNotNull(method, host, path).joinToString(" ").ifEmpty { stringResource(R.string.boggart_all_requests) }
}

@Composable
internal fun Mode.label(): String = stringResource(
    when (this) {
        Mode.Active -> R.string.boggart_mode_active
        Mode.Paused -> R.string.boggart_mode_paused
        Mode.Offline -> R.string.boggart_mode_offline
        Mode.Off -> R.string.boggart_mode_off
    },
)

@Composable
internal fun Mode.description(): String = stringResource(
    when (this) {
        Mode.Active -> R.string.boggart_mode_active_desc
        Mode.Paused -> R.string.boggart_mode_paused_desc
        Mode.Offline -> R.string.boggart_mode_offline_desc
        Mode.Off -> R.string.boggart_mode_off_banner
    },
)

/** 기본 모드면 null. */
@StringRes
internal fun Mode.bannerRes(): Int? = when (this) {
    Mode.Active -> null
    Mode.Paused -> R.string.boggart_mode_paused_banner
    Mode.Offline -> R.string.boggart_mode_offline_banner
    Mode.Off -> R.string.boggart_mode_off_banner
}

/**
 * 규칙 목록 두 번째 줄. 규칙이 무엇을 하는지 문장으로 쓴다. 지연이 있으면 덧붙인다.
 * 순차 응답이면 [nextStep](0부터)으로 다음 차례를 덧붙인다.
 */
@Composable
internal fun overrideSentence(override: Override, nextStep: Int? = null): String {
    val main = when (override) {
        is Override.Replace -> stringResource(R.string.boggart_desc_replace, override.status)
        is Override.Patch -> stringResource(R.string.boggart_desc_manual)
        is Override.JsonPatch -> pluralStringResource(R.plurals.boggart_desc_values, override.ops.size, override.ops.size)
        is Override.Fail -> when (val kind = override.kind) {
            FailKind.Timeout -> stringResource(R.string.boggart_desc_fail_timeout)
            FailKind.ConnectionReset -> stringResource(R.string.boggart_desc_fail_connection_reset)
            FailKind.UnknownHost -> stringResource(R.string.boggart_desc_fail_unknown_host)
            is FailKind.Custom -> stringResource(R.string.boggart_desc_fail_custom, kind.message)
        }
        is Override.Sequence -> {
            val size = override.steps.size
            val count = pluralStringResource(R.plurals.boggart_desc_sequence, size, size)
            when {
                nextStep == null -> count
                nextStep < size -> count + " · " + stringResource(R.string.boggart_sequence_next, nextStep + 1)
                else -> count + " · " + stringResource(R.string.boggart_sequence_used_up)
            }
        }
    }
    return if (override.delayMs > 0) main + " · " + stringResource(R.string.boggart_desc_delay, override.delayMs) else main
}

@Composable
internal fun CallStatus.label(): String = stringResource(
    when (this) {
        CallStatus.Original -> R.string.boggart_status_original
        CallStatus.Changed -> R.string.boggart_status_changed
        CallStatus.Failed -> R.string.boggart_status_failed
        CallStatus.NotApplied -> R.string.boggart_status_not_applied
    },
)

/** 상세 화면 배지 옆 설명 한 줄. 배지는 넷으로 묶었으므로 수정·교체, 규칙 실패·오프라인 차단은 여기서 구분한다. */
@Composable
internal fun Exchange.statusDescription(): String = stringResource(
    when (provenance) {
        Provenance.Original -> if (error != null) R.string.boggart_status_desc_network_error else R.string.boggart_status_desc_original
        Provenance.Patched -> R.string.boggart_status_desc_patched
        Provenance.PatchedInvalid -> R.string.boggart_status_desc_patched_invalid
        Provenance.Replaced -> R.string.boggart_status_desc_replaced
        // 규칙 없이 실패한 호출은 오프라인 모드가 막은 것뿐이다.
        Provenance.Failed -> if (appliedRuleId == null) R.string.boggart_status_desc_offline else R.string.boggart_status_desc_failed_rule
        Provenance.InvalidIgnored -> R.string.boggart_status_desc_not_applied
    },
)

/** 바이트 수를 B·KB·MB로. 언어와 무관한 표기다. */
internal fun formatBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
    else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
}
