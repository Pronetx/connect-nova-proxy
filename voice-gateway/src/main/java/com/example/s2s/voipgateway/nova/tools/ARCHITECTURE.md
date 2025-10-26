# Modular Tool Architecture

## Visual Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    NovaStreamerFactory                           │
│  (Creates handler with caller's phone number)                   │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         │ creates
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│           ModularNovaS2SEventHandler                             │
│  (Main handler that orchestrates tool invocations)               │
│                                                                   │
│  ┌────────────────────────────────────────────────────────┐     │
│  │            ToolRegistry                                 │     │
│  │  (Manages all registered tools)                         │     │
│  │                                                          │     │
│  │  ┌──────────────────────────────────────────────────┐  │     │
│  │  │  Tool Interface                                   │  │     │
│  │  │  - getName()                                      │  │     │
│  │  │  - getDescription()                               │  │     │
│  │  │  - getInputSchema()                               │  │     │
│  │  │  - handle(...)                                    │  │     │
│  │  └──────────────────────────────────────────────────┘  │     │
│  │           ▲        ▲        ▲         ▲                │     │
│  │           │        │        │         │                │     │
│  └───────────┼────────┼────────┼─────────┼────────────────┘     │
│              │        │        │         │                       │
│              │        │        │         │                       │
│        ┌─────┴──┐ ┌──┴────┐ ┌─┴─────┐ ┌─┴────────┐            │
│        │SendOTP │ │Verify │ │Hangup │ │DateTime  │ ...        │
│        │  Tool  │ │OTP    │ │ Tool  │ │  Tool    │            │
│        │        │ │Tool   │ │       │ │          │            │
│        └────────┘ └───────┘ └───────┘ └──────────┘            │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

## Request Flow

```
 Phone Call  ──┐
               │
         ┌─────▼────────┐
         │ Nova Sonic   │
         │  Decides to  │
         │  use a tool  │
         └─────┬────────┘
               │
               │ {toolName: "sendOTPTool", content: "{}"}
               │
         ┌─────▼────────────────────────────────────────┐
         │  ModularNovaS2SEventHandler                  │
         │                                               │
         │  handleToolInvocation(toolName, content...)  │
         └─────┬────────────────────────────────────────┘
               │
               │
         ┌─────▼────────────────────────────────────────┐
         │  ToolRegistry                                 │
         │                                               │
         │  handle(toolName, toolUseId, content, output)│
         │    - Looks up tool by name                   │
         │    - Delegates to tool.handle()              │
         └─────┬────────────────────────────────────────┘
               │
               │
         ┌─────▼────────────────────────────────────────┐
         │  SendOTPTool                                  │
         │                                               │
         │  handle(toolUseId, content, output) {        │
         │    - Generate 4-digit code                   │
         │    - Store in otpStore                       │
         │    - Send SMS via Pinpoint                   │
         │    - Populate output map                     │
         │  }                                            │
         └─────┬────────────────────────────────────────┘
               │
               │ output: {status: "success", message: "..."}
               │
         ┌─────▼────────────────────────────────────────┐
         │  Back to Nova Sonic                           │
         │  Nova reads the output and responds to caller │
         └───────────────────────────────────────────────┘
```

## Tool Lifecycle

```
1. INITIALIZATION
   ┌──────────────────────────────────────────────┐
   │ ModularNovaS2SEventHandler constructor      │
   │   ├─ Create ToolRegistry                    │
   │   ├─ Create PinpointClient                  │
   │   ├─ Create shared OTP store                │
   │   └─ Register tools:                        │
   │       ├─ new SendOTPTool(...)               │
   │       ├─ new VerifyOTPTool(...)             │
   │       └─ new HangupTool()                   │
   └──────────────────────────────────────────────┘

2. CONFIGURATION
   ┌──────────────────────────────────────────────┐
   │ getToolConfiguration()                       │
   │   ├─ Registry builds list of all tools      │
   │   ├─ Each tool provides:                    │
   │   │   ├─ name                                │
   │   │   ├─ description                         │
   │   │   └─ input schema                        │
   │   └─ Returns ToolConfiguration to Nova      │
   └──────────────────────────────────────────────┘

3. INVOCATION
   ┌──────────────────────────────────────────────┐
   │ handleToolInvocation(toolName, content...)   │
   │   ├─ Registry.handle(toolName...)           │
   │   ├─ Registry finds tool by name            │
   │   ├─ Tool.handle() executes                 │
   │   └─ Tool populates output map              │
   └──────────────────────────────────────────────┘

4. CLEANUP
   ┌──────────────────────────────────────────────┐
   │ close()                                      │
   │   └─ Close PinpointClient                   │
   └──────────────────────────────────────────────┘
```

## Adding a New Tool - Step by Step

```
Step 1: Create Tool Class
┌─────────────────────────────────────┐
│ public class MyTool implements Tool │
│   getName()        → "myTool"       │
│   getDescription() → "..."          │
│   getInputSchema() → {...}          │
│   handle(...)      → {...}          │
└─────────────────────────────────────┘
          │
          │
Step 2: Register Tool
┌─────────────────────────────────────┐
│ ModularNovaS2SEventHandler          │
│   initializeDefaultTools() {        │
│     registry.register(new MyTool()) │
│   }                                 │
└─────────────────────────────────────┘
          │
          │
Step 3: Done! ✓
┌─────────────────────────────────────┐
│ Nova can now use your tool          │
│ - Automatically included in config  │
│ - Auto-routed on invocation         │
│ - No other code changes needed      │
└─────────────────────────────────────┘
```

## Benefits of This Design

### 1. Separation of Concerns
```
┌──────────────────────┐  ┌──────────────────────┐
│ Tool Implementation  │  │ Tool Registration    │
│ (What it does)       │  │ (Where it's used)    │
└──────────────────────┘  └──────────────────────┘
         │                          │
         └──────────┬───────────────┘
                    │
              No coupling!
```

### 2. Easy Testing
```
@Test
public void testSendOTPTool() {
    // Mock dependencies
    PinpointClient mockClient = mock(PinpointClient.class);
    Map<String, String> otpStore = new HashMap<>();

    // Create tool in isolation
    SendOTPTool tool = new SendOTPTool(mockClient, "+1234567890", otpStore);

    // Test behavior
    Map<String, Object> output = new HashMap<>();
    tool.handle("test-id", "{}", output);

    // Verify
    assertEquals("success", output.get("status"));
    assertTrue(otpStore.containsKey("test-id"));
}
```

### 3. Reusability
```
// Same tools, different configurations

Handler 1 (OTP only)
┌────────────────────┐
│ - SendOTPTool      │
│ - VerifyOTPTool    │
└────────────────────┘

Handler 2 (DateTime only)
┌────────────────────┐
│ - DateTimeTool     │
│ - HangupTool       │
└────────────────────┘

Handler 3 (All tools)
┌────────────────────┐
│ - SendOTPTool      │
│ - VerifyOTPTool    │
│ - DateTimeTool     │
│ - HangupTool       │
│ - CustomTool       │
└────────────────────┘
```

### 4. Extensibility
```
Want to add a new tool?
Just implement the interface!

public class AccountBalanceTool implements Tool {
    // Implement 4 methods
    // Register in one place
    // Done!
}

No changes needed to:
- ModularNovaS2SEventHandler ✓
- ToolRegistry ✓
- Other tools ✓
- Nova integration ✓
```

## Comparison: Before vs After

### Before (Monolithic)
```java
class OTPNovaS2SEventHandler {
    handleToolInvocation(...) {
        switch (toolName) {
            case "sendOTPTool":
                // 30 lines of SMS code
                break;
            case "verifyOTPTool":
                // 30 lines of validation code
                break;
            case "hangupTool":
                // 20 lines of hangup code
                break;
            // Adding tool = modify this file
        }
    }
}
```

### After (Modular)
```java
class ModularNovaS2SEventHandler {
    ToolRegistry registry;

    handleToolInvocation(...) {
        registry.handle(toolName, ...);  // 1 line!
    }
}

class SendOTPTool implements Tool { /* ... */ }
class VerifyOTPTool implements Tool { /* ... */ }
class HangupTool implements Tool { /* ... */ }
// Adding tool = new file, no modifications
```

## Schema Definitions

Tools can share schemas or have custom ones:

```
ToolSpecs.java
├── DEFAULT_TOOL_SPEC         (no parameters)
├── OTP_VERIFY_TOOL_SPEC      (requires "code")
├── SEARCH_TOOL_SPEC          (requires "query")
└── ... add more as needed
```

Each tool references the schema it needs in `getInputSchema()`.
