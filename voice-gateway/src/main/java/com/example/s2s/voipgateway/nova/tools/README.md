# Nova Sonic Tools - Auto-Discovery Architecture

This package provides an **auto-discovery** tool system for Nova Sonic conversations. Just drop a new tool class in this folder and it's automatically available!

## Architecture Overview

```
Tool.java                       - Interface that all tools implement
ToolFactory.java                - Auto-discovers and creates tool instances
ToolRegistry.java               - Manages available tools
ToolSpecs.java                  - Common input schema definitions
ModularNovaS2SEventHandler.java - Handler that uses auto-discovery

Individual Tools (auto-discovered):
├── DateTimeTool.java          - Get current date/time
├── GetCallerPhoneTool.java    - Get caller's phone number
├── SendOTPTool.java           - Sends OTP codes via SMS
├── VerifyOTPTool.java         - Verifies OTP codes
├── HangupTool.java            - Ends the phone call
└── [Your Custom Tool]         - Just add it here!
```

## How to Add a New Tool

**Step 1: Create your tool class - that's it!**

```java
package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

public class MyCustomTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(MyCustomTool.class);

    // Optional: Constructor for dependency injection
    // Available: String (phone number), PinpointClient, Map<String,String> (OTP store)
    public MyCustomTool(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    @Override
    public String getName() {
        return "myCustomTool";  // Must be unique
    }

    @Override
    public String getDescription() {
        return "Description that tells Nova when to use this tool";
    }

    @Override
    public Map<String, String> getInputSchema() {
        return ToolSpecs.DEFAULT_TOOL_SPEC;  // or custom schema
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        log.info("MyCustomTool invoked");

        // Your tool logic here

        output.put("status", "success");
        output.put("message", "Tool executed successfully");
    }
}
```

**Step 2: Enable it in a prompt file (optional)**

Add to `src/main/resources/prompts/yourprompt.prompt`:

```
@tool myCustomTool
```

**That's it!** No registration needed. The tool is automatically discovered.

## Dependency Injection

The `ToolFactory` automatically injects dependencies based on constructor parameters:

| Constructor Parameter Type | Injected Value |
|---------------------------|----------------|
| `String` | Caller's phone number |
| `PinpointClient` | AWS Pinpoint client for SMS |
| `Map<String, String>` | Shared OTP store (for OTP tools) |

**Examples:**

```java
// No dependencies
public DateTimeTool() { }

// Phone number only
public GetCallerPhoneTool(String phoneNumber) {
    this.phoneNumber = phoneNumber;
}

// Multiple dependencies
public SendOTPTool(PinpointClient client, String phoneNumber, Map<String, String> otpStore) {
    this.pinpointClient = client;
    this.phoneNumber = phoneNumber;
    this.otpStore = otpStore;
}
```

## Available Tools

### DateTimeTool
- **Name**: `getDateTimeTool`
- **Purpose**: Get current date and time
- **Dependencies**: None
- **Input**: None

### GetCallerPhoneTool
- **Name**: `getCallerPhoneTool`
- **Purpose**: Retrieve the caller's phone number
- **Dependencies**: Phone number
- **Input**: None

### SendOTPTool
- **Name**: `sendOTPTool`
- **Purpose**: Generate and send authentication code via SMS
- **Dependencies**: PinpointClient, phone number, OTP store
- **Input**: None

### VerifyOTPTool
- **Name**: `verifyOTPTool`
- **Purpose**: Verify authentication code
- **Dependencies**: OTP store
- **Input**: `otp` (string), `sessionId` (string)

### HangupTool
- **Name**: `hangupTool`
- **Purpose**: End the current call
- **Dependencies**: None
- **Input**: None

## Custom Input Schemas

If your tool needs parameters, define a schema in `ToolSpecs.java`:

```java
// In ToolSpecs.java
public static final Map<String, String> MY_TOOL_SPEC;

static {
    Map<String, String> spec = new HashMap<>();
    try {
        Map<String, Object> accountProperty = new HashMap<>();
        accountProperty.put("type", "string");
        accountProperty.put("description", "Account number to search");

        Map<String, Object> properties = new HashMap<>();
        properties.put("accountNumber", accountProperty);

        spec.put("json",
                new ObjectMapper().writeValueAsString(PromptStartEvent.ToolSchema.builder()
                        .type("object")
                        .properties(properties)
                        .required(Collections.singletonList("accountNumber"))
                        .build()));
    } catch (JsonProcessingException e) {
        throw new RuntimeException("Failed to serialize schema!", e);
    }
    MY_TOOL_SPEC = Collections.unmodifiableMap(spec);
}
```

Then use it in your tool:

```java
@Override
public Map<String, String> getInputSchema() {
    return ToolSpecs.MY_TOOL_SPEC;
}
```

## How Auto-Discovery Works

1. `ToolFactory` scans the `com.example.s2s.voipgateway.nova.tools` package at runtime
2. Finds all classes implementing the `Tool` interface
3. For each tool class:
   - Examines available constructors
   - Matches constructor parameters to available dependencies
   - Creates an instance if dependencies can be satisfied
4. Returns list of successfully created tools

This means:
- ✅ Add new tools by just creating the class
- ✅ Remove tools by deleting the class
- ✅ No manual registration needed
- ✅ No code changes to ModularNovaS2SEventHandler

## Example: Adding a Weather Tool

```java
package com.example.s2s.voipgateway.nova.tools;

public class WeatherTool implements Tool {
    @Override
    public String getName() {
        return "getWeatherTool";
    }

    @Override
    public String getDescription() {
        return "Get the current weather conditions";
    }

    @Override
    public Map<String, String> getInputSchema() {
        return ToolSpecs.DEFAULT_TOOL_SPEC;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) {
        // Call weather API
        output.put("weather", "Sunny, 72°F");
        output.put("status", "success");
    }
}
```

Then add to a prompt file:
```
@tool getWeatherTool
```

Done! The tool is now available.

## Best Practices

1. **Tool Names**: Use camelCase ending with "Tool" (e.g., `sendEmailTool`)
2. **Descriptions**: Be specific - Nova uses this to decide when to invoke
3. **Error Handling**: Throw exceptions or set error status in output
4. **Logging**: Use SLF4J logger for debugging
5. **Output Format**: Use consistent keys: `status`, `message`, `result`
6. **Constructor**: Only request dependencies you actually need
7. **Testing**: Test tools independently before integration

## Testing Tools

```java
@Test
public void testMyCustomTool() {
    MyCustomTool tool = new MyCustomTool("+15551234567");
    Map<String, Object> output = new HashMap<>();

    tool.handle("test-id", "{}", output);

    assertEquals("success", output.get("status"));
    assertNotNull(output.get("message"));
}
```

## Troubleshooting

**Tool not being discovered?**
- Check that it's in `com.example.s2s.voipgateway.nova.tools` package
- Verify it implements `Tool` interface
- Ensure it has a public constructor
- Check logs for "Discovered X tool classes"

**Tool failing to create?**
- Constructor parameters must match available dependencies
- Check logs for "No compatible constructor found"
- Verify dependencies are available (e.g., PinpointClient is initialized)

**Tool not being invoked by Nova?**
- Check that tool name matches in prompt file (`@tool toolName`)
- Verify tool description is clear enough for Nova
- Review logs to see if tool was registered
