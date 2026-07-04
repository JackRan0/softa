# The `{{ }}` Placeholder & Expression Syntax

Part of the [Softa app authoring guide](../README.md). Softa uses one placeholder
syntax, `{{ ... }}`, across computed fields, filters, and templates. This is what
a downstream app needs to know.

---

## The three kinds of `{{ }}`

| Kind | Looks like | Where |
|---|---|---|
| **Variable** | `{{ TriggerParams.status }}` | dot-path lookup from a context map |
| **Reserved field** | `{{ @createdTime }}` | reference to *another field on the same row* (filters) |
| **Expression** | `{{ price * qty }}` | an AviatorScript formula, evaluated live |

Variables resolve dotted paths against a context map (e.g.
`TriggerParams.owner.name`). Expressions are full AviatorScript — arithmetic,
comparisons, string ops, and a set of imported helpers (date/time, string
utilities).

---

## Computed fields

A computed field derives its value from other fields of the same row. Declare it
with `computed = true` and an `expression`:

```java
@Field(label = "Full Name", computed = true, expression = "firstName + ' ' + lastName")
private String fullName;

@Field(label = "Line Total", computed = true, expression = "unitPrice * quantity")
private BigDecimal lineTotal;
```

Key point: **the `expression` is plain AviatorScript — it is NOT wrapped in
`{{ }}`.** The `{{ }}` wrapper is for template *text* (see below); the
`expression` attribute is already known to be an expression. Variables in scope
are the other fields of the same row (referenced by their field name).

Computed fields are evaluated on read; they are not stored columns.

---

## Filters — comparing one field to another

In a filter, `{{ @fieldName }}` refers to another field on the same row, so you
can express field-to-field conditions (rather than field-to-constant):

```
["endDate", ">=", "{{ @startDate }}"]
```

---

## Templates (documents, messages, default values)

Free text with `{{ variable }}` placeholders is rendered against a context map:

```
Order {{ TriggerParams.id }} is {{ TriggerParams.status }}
```
→ with context `{TriggerParams: {id: 1001, status: "PAID"}}` →
`Order 1001 is PAID`.

You'll hit this in message/document templates and in `@Field(defaultValue = "…")`
expressions. The rendering entry points live in `io.softa.framework.base.placeholder`
(`PlaceholderUtils`, `TemplateEngine`) if you need to render a template yourself
in service code.

---

## Where you'll use it

| Touchpoint | Form |
|---|---|
| Computed field | `@Field(computed = true, expression = "…")` — bare AviatorScript |
| Default value | `@Field(defaultValue = "…")` — expression evaluated on insert |
| Filter field-to-field | `{{ @otherField }}` inside a filter value |
| Message / document templates | `{{ variable }}` in the template text |

---

## Common mistakes

1. **Wrapping a computed `expression` in `{{ }}`.** The `expression` attribute is
   raw AviatorScript — write `unitPrice * quantity`, not `{{ unitPrice * quantity }}`.
2. **Using `{{ @field }}` outside a filter.** The reserved-field form is for
   filter conditions (same-row field reference). In computed fields you reference
   other fields by their plain name.
3. **Referencing a field that isn't on the row.** A computed expression can only
   see fields of the same model row (plus the imported helpers).
