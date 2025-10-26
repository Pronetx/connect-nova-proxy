# Call Disposition Types

**Shared Interface Concept**

This document defines the call disposition types used across both modules. This is not executable code, but a contract that both Java (voice-gateway, connect-integration) and JavaScript (lambdas) implementations should follow.

## CallDisposition Enum

Represents the final outcome of a call and determines post-transfer routing.

### Java Interface

```java
package com.example.s2s.shared;

public enum CallDisposition {
    /**
     * Customer's issue was resolved by Nova, call should end
     */
    END,

    /**
     * Customer needs to speak with a human agent
     */
    AGENT,

    /**
     * Customer should be routed to post-call satisfaction survey
     */
    SURVEY;

    /**
     * Convert to attribute value (lowercase string)
     */
    public String toAttributeValue() {
        return this.name().toLowerCase();
    }

    /**
     * Parse from attribute value
     */
    public static CallDisposition fromAttributeValue(String value) {
        return CallDisposition.valueOf(value.toUpperCase());
    }
}
```

**Usage in Connect Integration:**
```java
// In ConnectEndCallTool
CallDisposition disposition = CallDisposition.AGENT;
attributes.put("nova_next", disposition.toAttributeValue()); // "agent"
```

---

### JavaScript/TypeScript Interface

```typescript
// lambdas/shared/types.mjs

export const CallDisposition = {
  END: 'end',
  AGENT: 'agent',
  SURVEY: 'survey'
} as const;

export type CallDispositionType = typeof CallDisposition[keyof typeof CallDisposition];

/**
 * Validate call disposition value
 */
export function isValidCallDisposition(value: string): value is CallDispositionType {
  return Object.values(CallDisposition).includes(value as CallDispositionType);
}
```

**Usage in Lambda:**
```javascript
import { CallDisposition, isValidCallDisposition } from '../shared/types.mjs';

// In Connect flow Lambda
const novaNext = event.Details.ContactData.Attributes.nova_next;

if (!isValidCallDisposition(novaNext)) {
  console.error('Invalid nova_next value:', novaNext);
  return { disposition: CallDisposition.END }; // Safe fallback
}

switch (novaNext) {
  case CallDisposition.AGENT:
    return { action: 'TransferToQueue', queue: 'GeneralSupport' };
  case CallDisposition.SURVEY:
    return { action: 'PlaySurvey' };
  case CallDisposition.END:
    return { action: 'Disconnect' };
}
```

---

## CallReason Enum

Represents the reason why a call is ending or being transferred.

### Values

```java
public enum CallReason {
    ISSUE_RESOLVED,        // "issue_resolved"
    NEEDS_AGENT,          // "needs_agent"
    NEEDS_ESCALATION,     // "needs_escalation"
    CUSTOMER_HANGUP,      // "customer_hangup"
    TECHNICAL_ISSUE,      // "technical_issue"
    OUT_OF_SCOPE,         // "out_of_scope"
    POLICY_QUESTION;      // "policy_question"

    public String toAttributeValue() {
        return this.name().toLowerCase().replace('_', '_');
    }
}
```

### JavaScript

```javascript
export const CallReason = {
  ISSUE_RESOLVED: 'issue_resolved',
  NEEDS_AGENT: 'needs_agent',
  NEEDS_ESCALATION: 'needs_escalation',
  CUSTOMER_HANGUP: 'customer_hangup',
  TECHNICAL_ISSUE: 'technical_issue',
  OUT_OF_SCOPE: 'out_of_scope',
  POLICY_QUESTION: 'policy_question'
} as const;
```

---

## IssueType Enum

Categorizes the customer's issue.

### Values

```java
public enum IssueType {
    BILLING,
    TECHNICAL,
    ACCOUNT,
    PRODUCT_INFO,
    COMPLAINT,
    GENERAL;
}
```

### JavaScript

```javascript
export const IssueType = {
  BILLING: 'billing',
  TECHNICAL: 'technical',
  ACCOUNT: 'account',
  PRODUCT_INFO: 'product_info',
  COMPLAINT: 'complaint',
  GENERAL: 'general'
} as const;
```

---

## CustomerSentiment Enum

Represents the detected customer sentiment.

### Values

```java
public enum CustomerSentiment {
    SATISFIED,
    NEUTRAL,
    FRUSTRATED,
    ANGRY,
    CONFUSED;
}
```

### JavaScript

```javascript
export const CustomerSentiment = {
  SATISFIED: 'satisfied',
  NEUTRAL: 'neutral',
  FRUSTRATED: 'frustrated',
  ANGRY: 'angry',
  CONFUSED: 'confused'
} as const;
```

---

## Implementation Guidelines

### For Java Modules (voice-gateway, connect-integration)

1. **Create Enum Classes:**
   - Location: `src/main/java/com/example/s2s/shared/`
   - Use uppercase enum constants
   - Provide `toAttributeValue()` for Connect API
   - Provide `fromAttributeValue()` for parsing

2. **Validation:**
   ```java
   public static void validate(String disposition) {
       try {
           CallDisposition.fromAttributeValue(disposition);
       } catch (IllegalArgumentException e) {
           throw new InvalidAttributeException("Invalid disposition: " + disposition);
       }
   }
   ```

3. **Serialization:**
   - Use lowercase strings for Connect attributes
   - Use enum constants internally
   - Never expose enum `.name()` directly to Connect

### For JavaScript Modules (lambdas)

1. **Use Constants Objects:**
   - Location: `lambdas/shared/types.mjs`
   - Use lowercase string values (match Connect attributes)
   - Export as `const` objects with TypeScript type annotations

2. **Validation:**
   ```javascript
   function validateDisposition(value) {
     if (!Object.values(CallDisposition).includes(value)) {
       throw new Error(`Invalid disposition: ${value}`);
     }
   }
   ```

3. **Type Safety:**
   - Use TypeScript `as const` for compile-time checking
   - Provide type guard functions (`isValidX()`)

---

## Cross-Module Consistency

**Critical:** Both Java and JavaScript implementations MUST use identical string values for Connect attributes.

### Testing Consistency

**Test File:** `shared/tests/type-consistency.test.js`

```javascript
import { CallDisposition } from '../types/CallDisposition.js';

describe('Type Consistency', () => {
  test('CallDisposition values match Java enum', () => {
    expect(CallDisposition.END).toBe('end');
    expect(CallDisposition.AGENT).toBe('agent');
    expect(CallDisposition.SURVEY).toBe('survey');
  });

  test('All CallDisposition values are lowercase', () => {
    Object.values(CallDisposition).forEach(value => {
      expect(value).toBe(value.toLowerCase());
    });
  });
});
```

### Schema Validation

**JSON Schema** for CI/CD validation:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "definitions": {
    "CallDisposition": {
      "type": "string",
      "enum": ["end", "agent", "survey"]
    },
    "CallReason": {
      "type": "string",
      "enum": [
        "issue_resolved",
        "needs_agent",
        "needs_escalation",
        "customer_hangup",
        "technical_issue",
        "out_of_scope",
        "policy_question"
      ]
    }
  }
}
```

---

## Version Control

When adding new enum values:

1. **Update all implementations:**
   - Java enums in both modules
   - JavaScript constants
   - JSON schema
   - This documentation

2. **Backwards compatibility:**
   - Never remove existing values
   - Connect flows may reference old values
   - Add new values at the end

3. **Announce changes:**
   - Update CHANGELOG.md
   - Notify both Dev A and Dev B
   - Update integration tests

---

## References

- [Attribute Contract](../docs/attribute-contract.md) - Full attribute specification
- [Call Flow Sequence](../docs/call-flow-sequence.md) - How these types are used in practice
