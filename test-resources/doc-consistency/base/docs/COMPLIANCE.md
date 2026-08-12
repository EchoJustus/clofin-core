# Fixture — Control Design and Mapping

Synthetic fixture for `clofin.tools.doc-consistency-test`. Not a CloFin
document: it exists to be contradicted on purpose.

## 1. How to read this

Status: ✅ enforced · 🔨 partial · 📋 designed, not yet built

---

## 2. Controls

### C-01 Segregation of duties ✅

**Statement.** The actor who submits must not be the actor who approves.

**Enforcement point.** A pure domain rule ✅.

---

### C-02 Dual authorisation ✅

**Statement.** The number of approvals required rises with the amount.

**Enforcement point.** A threshold table ✅.

---

### C-07 Sanctions screening before release 📋

**Statement.** No instruction is released before screening completes.

**Enforcement point.** State machine precondition. *(Not built.)*
