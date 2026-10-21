"""Pure selection and time budgeting for reviewed release read plans.

This module performs no network, database, process or deployment operations.
Selection applies to explicitly unchanged compatibility checks. Changed business
checks and complete database preservation remain the caller's separate duties.
"""
import copy
import hashlib
import json
import math
from collections.abc import Mapping


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def fingerprint(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def representatives(items, classify, *, max_cases):
    """Keep one deterministic case per explicit coverage class; never truncate.

    The caller supplies a reviewed classifier including role, permission result,
    data state and pagination boundaries as appropriate. All members of a class
    must have the same intended risk coverage. The original list is untouched.
    """
    if type(max_cases) is not int or max_cases < 1 or not callable(classify):
        raise ValueError('POSITIVE_CASE_LIMIT_AND_EXPLICIT_CLASSIFIER_REQUIRED')
    if not isinstance(items, list) or not items:
        raise ValueError('NONEMPTY_ORIGINAL_PLAN_REQUIRED')
    groups = {}
    identities = set()
    for item in items:
        if not isinstance(item, Mapping):
            raise ValueError('CASE_OBJECT_REQUIRED')
        identity = fingerprint(item)
        if identity in identities:
            raise ValueError('DUPLICATE_ORIGINAL_CASE')
        identities.add(identity)
        coverage = classify(copy.deepcopy(item))
        if not isinstance(coverage, (list, tuple)) or not coverage:
            raise ValueError('NONEMPTY_EXPLICIT_COVERAGE_CLASS_REQUIRED')
        key = canonical(coverage)
        if key not in groups or identity < groups[key][0]:
            groups[key] = (identity, copy.deepcopy(item))
    if len(groups) > max_cases:
        raise ValueError('COVERAGE_EXCEEDS_BUDGET_REFINE_PLAN_WITHOUT_TRUNCATION')
    selected = [value[1] for _, value in sorted(groups.items())]
    return dict(originalCount=len(items), selectedCount=len(selected),
                coverageClassCount=len(groups), items=selected,
                originalPlanSha256=fingerprint(items), selectedPlanSha256=fingerprint(selected),
                completeOriginalPlanExecuted=False)


def _number(value, name, *, positive=False):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0 or (positive and value == 0):
        raise ValueError('INVALID_' + name)
    return value


def budget(groups, *, maintenance_seconds=1800, recovery_seconds, safety_factor=1.25):
    """Size every planned group from measured request cost and fixed work.

    Passing this arithmetic check does not authorize production actions or prove
    backup recovery, current data preservation, CI or runtime qualification.
    Unknown costs are rejected rather than silently treated as zero.
    """
    _number(maintenance_seconds, 'MAINTENANCE_LIMIT', positive=True)
    _number(recovery_seconds, 'RECOVERY_RESERVE', positive=True)
    _number(safety_factor, 'SAFETY_FACTOR', positive=True)
    if safety_factor < 1:
        raise ValueError('SAFETY_FACTOR_CANNOT_REDUCE_ESTIMATE')
    if not isinstance(groups, list) or not groups:
        raise ValueError('NONEMPTY_WORK_GROUPS_REQUIRED')
    names = set()
    online = maintenance = 0
    estimates = []
    for group in groups:
        required = {'name', 'maintenance', 'requests', 'secondsPerRequest', 'fixedSeconds'}
        if not isinstance(group, dict) or set(group) != required:
            raise ValueError('EXACT_WORK_GROUP_FIELDS_REQUIRED')
        name = group['name']
        if type(name) is not str or not name or name in names:
            raise ValueError('UNIQUE_WORK_GROUP_NAMES_REQUIRED')
        names.add(name)
        count = group['requests']
        if type(count) is not int or count < 0 or type(group['maintenance']) is not bool:
            raise ValueError('EXACT_REQUEST_COUNT_AND_MAINTENANCE_SCOPE_REQUIRED')
        per_request = _number(group['secondsPerRequest'], 'MEASURED_REQUEST_COST', positive=count > 0)
        fixed = _number(group['fixedSeconds'], 'MEASURED_FIXED_COST')
        if count == 0 and fixed == 0:
            raise ValueError('EMPTY_WORK_GROUP')
        seconds = math.ceil((count * per_request + fixed) * safety_factor)
        estimates.append(dict(name=name, requests=count, maintenance=group['maintenance'], estimatedSeconds=seconds))
        if group['maintenance']:
            maintenance += seconds
        else:
            online += seconds
    required_seconds = math.ceil(maintenance + recovery_seconds)
    return dict(groups=estimates, maintenanceLimitSeconds=maintenance_seconds,
                maintenanceWorkSeconds=maintenance, onlineWorkSeconds=online,
                recoveryReserveSeconds=recovery_seconds,
                requiredMaintenanceSeconds=required_seconds,
                budgetSatisfied=required_seconds <= maintenance_seconds,
                productionActionsAuthorized=False)
