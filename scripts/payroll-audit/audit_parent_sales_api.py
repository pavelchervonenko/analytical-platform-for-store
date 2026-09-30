#!/usr/bin/env python3
"""Operator-run, bounded LiveSklad read-only preflight. No SQL or recovery calls."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import pwd
import re
import ssl
import sys
import tempfile
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from urllib import request, error, parse

MANIFEST_SHA256 = "888bde2710d27ab725648273fdb3e1d0133e5b3f0b2389bf94c3d9c34dbb453b"
MAX_BODY = 8 * 1024 * 1024


class AuditError(Exception):
    pass


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_targets(path):
    content = path.read_bytes()
    if hashlib.sha256(content).hexdigest() != MANIFEST_SHA256:
        raise AuditError("Target manifest checksum mismatch")
    manifest = json.loads(content)
    targets = manifest["targets"]
    if manifest.get("contract") != "six-parent-sales-audit-v1" or len(targets) != 6:
        raise AuditError("Unexpected audit scope")
    for key in ("return_id", "parent_id"):
        if len({t[key] for t in targets}) != 6:
            raise AuditError("Duplicate targets")
    for target in targets:
        for key in ("return_id", "parent_id", "store_id", "position_id", "original_position_id", "product_id"):
            if not re.fullmatch("[0-9a-f]{24}", target[key]):
                raise AuditError("Invalid source identity")
    return manifest


def number(value):
    if value is None or isinstance(value, bool):
        return None
    try:
        result = Decimal(str(value))
        return result if result.is_finite() else None
    except InvalidOperation:
        return None


def identifier(value):
    return value.get("id") if isinstance(value, dict) else None


def date_value(value):
    if not isinstance(value, str):
        return None
    # Accept PostgreSQL/provider fractional seconds in Python 3.10.
    value = value.replace("Z", "+00:00")
    for pattern in ("%Y-%m-%dT%H:%M:%S.%f%z", "%Y-%m-%dT%H:%M:%S%z"):
        try:
            return datetime.strptime(value, pattern)
        except ValueError:
            pass
    return None


def position_summary(position):
    quantity, unit = number(position.get("count")), number(position.get("soldPrice"))
    return dict(position_id=position.get("positionId"),
                original_position_id=position.get("salePositionId"),
                product_id=position.get("nomenclatureId"), quantity=quantity,
                net_amount=quantity * unit if quantity is not None and unit is not None else None,
                cost_amount=number(position.get("purchasePriceSumm")))


def document_summary(doc):
    positions = doc.get("positions")
    return dict(id=doc.get("id"), number=doc.get("number"), type=doc.get("type"),
                date=doc.get("date"), store_id=identifier(doc.get("shop")),
                employee_id=identifier(doc.get("customer")),
                parent_id=identifier(doc.get("parentDocument")),
                status_fields={k: doc[k] for k in ("status", "state", "deleted", "isDeleted")
                               if k in doc and isinstance(doc[k], (str, bool, int, type(None)))},
                positions_shape_valid=isinstance(positions, list) and all(isinstance(p, dict) for p in positions),
                positions=[position_summary(p) for p in positions if isinstance(p, dict)]
                if isinstance(positions, list) else None)


def assess(target, returned, parent):
    issues = []
    r, p = document_summary(returned), document_summary(parent)
    for label, doc, expected_id, kind in (
            ("RETURN", r, target["return_id"], "salereturn"),
            ("PARENT", p, target["parent_id"], "sale")):
        if doc["id"] != expected_id:
            issues.append(label + "_ID_MISMATCH")
        if str(doc["type"]).lower() != kind:
            issues.append(label + "_TYPE_MISMATCH")
        if doc["store_id"] != target["store_id"]:
            issues.append(label + "_STORE_MISMATCH")
        if date_value(doc["date"]) is None:
            issues.append(label + "_DATE_INVALID")
        if any(doc["status_fields"].get(k) is True for k in ("deleted", "isDeleted")):
            issues.append(label + "_DELETED")
        positions = doc["positions"]
        if not doc["positions_shape_valid"] or positions is None or not positions or any(not x["position_id"] for x in positions):
            issues.append(label + "_POSITIONS_INVALID")
        elif len({x["position_id"] for x in positions}) != len(positions):
            issues.append(label + "_DUPLICATE_POSITIONS")
    if r["parent_id"] != target["parent_id"]:
        issues.append("RETURN_PARENT_LINK_CHANGED")
    if not p["employee_id"]:
        issues.append("PARENT_EMPLOYEE_MISSING")
    rd, pd = date_value(r["date"]), date_value(p["date"])
    if rd is not None and pd is not None and pd > rd:
        issues.append("PARENT_AFTER_RETURN")
    rp = r["positions"] or []
    pp = [x for x in p["positions"] or [] if x["position_id"] == target["original_position_id"]]
    if len(rp) != 1 or rp[0]["position_id"] != target["position_id"]:
        issues.append("RETURN_POSITION_SET_CHANGED")
    else:
        item = rp[0]
        if item["original_position_id"] != target["original_position_id"]:
            issues.append("ORIGINAL_POSITION_LINK_CHANGED")
        if item["product_id"] != target["product_id"]:
            issues.append("RETURN_PRODUCT_MISMATCH")
        for key in ("quantity", "net_amount", "cost_amount"):
            if item[key] != number(target[key]):
                issues.append("RETURN_" + key.upper() + "_CHANGED")
    if len(pp) != 1:
        issues.append("ORIGINAL_POSITION_NOT_UNIQUE_OR_MISSING")
    else:
        item = pp[0]
        if item["product_id"] != target["product_id"]:
            issues.append("PARENT_PRODUCT_MISMATCH")
        if item["quantity"] is None or item["quantity"] < number(target["quantity"]):
            issues.append("PARENT_QUANTITY_INSUFFICIENT")
        if item["net_amount"] is None or item["cost_amount"] is None:
            issues.append("PARENT_AMOUNTS_INCOMPLETE")
    return dict(return_id=target["return_id"], parent_id=target["parent_id"],
                returned=r, parent=p, issues=issues,
                explicit_parent_deletion_flag=any(k in p["status_fields"] for k in ("deleted", "isDeleted")),
                state="SOURCE_REVIEW_ONLY", recovery_approved=False, applied=False)


def fetch_json(opener, url, *, headers=None, data=None):
    req = request.Request(url, data=data, headers=headers or {},
                          method="POST" if data is not None else "GET")
    with opener.open(req, timeout=30) as response:
        body = response.read(MAX_BODY + 1)
        if len(body) > MAX_BODY:
            raise AuditError("Provider response exceeded audit limit")
        return json.loads(body, parse_float=Decimal)


def config():
    helper = Path("/opt/store-analytics/scripts/lib/script_security.py")
    spec = importlib.util.spec_from_file_location("audit_security", helper)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    values = module.parse_dotenv(Path("/etc/store-analytics/release.env"),
                                 ["LIVESKLAD_BASE_URL", "LIVESKLAD_LOGIN_FILE", "LIVESKLAD_PASSWORD_FILE"])
    base = module.normalize_base_url(values["LIVESKLAD_BASE_URL"], "https-only")
    credentials = {}
    for key, field in (("LIVESKLAD_LOGIN_FILE", "login"), ("LIVESKLAD_PASSWORD_FILE", "password")):
        path = Path(values[key])
        if not path.is_absolute() or path.is_symlink() or not path.is_file() or path.stat().st_size > 65536:
            raise AuditError("Credential file unavailable")
        value = path.read_text().rstrip("\r\n")
        if not value:
            raise AuditError("Empty credential file")
        credentials[field] = value
    return base, credentials


def main():
    if len(sys.argv) != 1 or os.geteuid() != 0:
        raise AuditError("Run with sudo python3, without arguments")
    os.umask(0o077)
    manifest = load_targets(Path(__file__).with_name("targets.json"))
    base, credentials = config()
    opener = request.build_opener(request.ProxyHandler({}), NoRedirect(),
                                  request.HTTPSHandler(context=ssl.create_default_context()))
    auth = fetch_json(opener, base + "/auth",
                      headers={"Content-Type": "application/x-www-form-urlencoded"},
                      data=parse.urlencode(credentials).encode())
    credentials.clear()
    token = auth.get("token")
    if not isinstance(token, str) or not token or any(ord(c) < 32 or ord(c) == 127 for c in token):
        raise AuditError("Authentication did not provide a valid token")
    started = datetime.now(timezone.utc).isoformat()
    results = []
    for target in manifest["targets"]:
        docs, failures = {}, []
        for key in ("return_id", "parent_id"):
            try:
                envelope = fetch_json(opener, base + "/documents/" + target[key],
                                      headers={"Authorization": token})
                if not isinstance(envelope, dict) or not isinstance(envelope.get("data"), dict):
                    failures.append(dict(target=key, reason="INVALID_DATA_ENVELOPE"))
                else:
                    docs[key] = envelope["data"]
            except error.HTTPError as exc:
                failures.append(dict(target=key, reason="HTTP_ERROR", status=exc.code))
                exc.close()
            except (error.URLError, TimeoutError, ValueError, AuditError, OSError):
                failures.append(dict(target=key, reason="REQUEST_OR_RESPONSE_ERROR"))
        if failures:
            results.append(dict(return_id=target["return_id"], parent_id=target["parent_id"],
                                request_failures=failures, recovery_approved=False, applied=False))
        else:
            results.append(assess(target, docs["return_id"], docs["parent_id"]))
    token = None
    result = dict(contract="six-parent-sales-api-review-v1", scope="READ_ONLY_SOURCE_REVIEW",
                  started_at=started, finished_at=datetime.now(timezone.utc).isoformat(),
                  manifest_sha256=MANIFEST_SHA256, source_export_sha256=manifest["source_export_sha256"],
                  script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  results=results, applied=False, recovery_approved=False,
                  limitation="No database changes. Source read is not an authorization to import or relink.")
    account = pwd.getpwnam("pavel")
    root = Path("/home/pavel")
    if root.is_symlink() or not root.is_dir():
        raise AuditError("Unexpected audit output directory")
    output = Path(tempfile.mkdtemp(prefix="payroll-parent-api-", dir=root))
    path = output / "source-check.json"
    with path.open("x") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2, default=str)
        stream.write("\n")
    checksum = output / "source-check.sha256"
    checksum.write_text(hashlib.sha256(path.read_bytes()).hexdigest() + "  source-check.json\n")
    for file in (path, checksum):
        os.chmod(file, 0o600)
        os.chown(file, account.pw_uid, account.pw_gid)
    os.chown(output, account.pw_uid, account.pw_gid)
    print("Готово:", output)
    print("Документов возврата:", len(results), "(проверка, не исправление)")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Never print provider payloads, credential paths/values or an HTTP exception body.
        print("Audit failed safely; no database changes. Check configuration/connectivity.", file=sys.stderr)
        sys.exit(1)
