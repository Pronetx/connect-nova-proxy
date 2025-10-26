# Nova Sonic Tools - Modular Architecture

This package provides a modular, extensible architecture for adding tools to Nova Sonic conversations.

## Architecture Overview

```
Tool.java              - Interface that all tools implement
ToolRegistry.java      - Central registry for managing tools
ToolSpecs.java         - Common input schema definitions
ModularNovaS2SEventHandler.java - Handler that uses the registry

Individual Tools:
├── SendOTPTool.java   - Sends OTP codes via SMS
├── VerifyOTPTool.java - Verifies OTP codes
├── HangupTool.java    - Ends the phone call
└── [Your Custom Tool] - Easy to add!
```

## How to Add a New Tool

### Step 1: Create Your Tool Class

Implement the `Tool` interface:

```java
package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

public class MyCustomTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(MyCustomTool.class);

    @Override
    public String getName() {
        return "myCustomTool";  // Unique name
    }

    @Override
    public String getDescription() {
        return "Description that tells Nova when to use this tool";
    }

    @Override
    public Map<String, String> getInputSchema() {
        // Use ToolSpecs.DEFAULT_TOOL_SPEC for no parameters
        // Or create a custom schema in ToolSpecs.java
        return ToolSpecs.DEFAULT_TOOL_SPEC;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        log.info("MyCustomTool invoked with content: {}", content);

        // Your tool logic here
        // Parse content if needed (JSON string)
        // Perform your action

        // Populate output
        output.put("status", "success");
        output.put("message", "Tool executed successfully");
        output.put("result", someData);
    }
}
```

### Step 2: Add Input Schema (if needed)

If your tool needs parameters, add a schema to `ToolSpecs.java`:

```java
// In ToolSpecs.java static block:
Map<String, String> myCustomToolSpec = new HashMap<>();
try {
    Map<String, Object> param1Property = new HashMap<>();
    param1Property.put("type", "string");
    param1Property.put("description", "Description of parameter");

    Map<String, Object> properties = new HashMap<>();
    properties.put("param1", param1Property);

    myCustomToolSpec.put("json",
            new ObjectMapper().writeValueAsString(PromptStartEvent.ToolSchema.builder()
                    .type("object")
                    .properties(properties)
                    .required(Collections.singletonList("param1"))
                    .build()));
} catch (JsonProcessingException e) {
    throw new RuntimeException("Failed to serialize schema!", e);
}
MY_CUSTOM_TOOL_SPEC = Collections.unmodifiableMap(myCustomToolSpec);
```

### Step 3: Register Your Tool

#### Option A: Modify ModularNovaS2SEventHandler

Add your tool in `initializeDefaultTools()`:

```java
// In ModularNovaS2SEventHandler.initializeDefaultTools()
toolRegistry.register(new MyCustomTool());
```

#### Option B: Create Custom Handler

```java
ToolRegistry registry = new ToolRegistry();
registry.register(new SendOTPTool(...));
registry.register(new MyCustomTool());
registry.register(new HangupTool());

ModularNovaS2SEventHandler handler = new ModularNovaS2SEventHandler(registry);
```

#### Option C: Runtime Registration

```java
ModularNovaS2SEventHandler handler = new ModularNovaS2SEventHandler(phoneNumber);
handler.getToolRegistry().register(new MyCustomTool());
```

## Example Tools

### Simple Tool (No Parameters)

```java
public class GetWeatherTool implements Tool {
    @Override
    public String getName() {
        return "getWeatherTool";
    }

    @Override
    public String getDescription() {
        return "Get the current weather information";
    }

    @Override
    public Map<String, String> getInputSchema() {
        return ToolSpecs.DEFAULT_TOOL_SPEC;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) {
        output.put("weather", "Sunny, 72°F");
        output.put("status", "success");
    }
}
```

### Tool with Parameters

```java
public class SearchDatabaseTool implements Tool {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "searchDatabaseTool";
    }

    @Override
    public String getDescription() {
        return "Search the database for a customer by account number";
    }

    @Override
    public Map<String, String> getInputSchema() {
        return ToolSpecs.SEARCH_DATABASE_TOOL_SPEC; // Define in ToolSpecs.java
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        JsonNode params = objectMapper.readTree(content);
        String accountNumber = params.get("accountNumber").asText();

        // Search database
        Customer customer = database.search(accountNumber);

        if (customer != null) {
            output.put("status", "success");
            output.put("customerName", customer.getName());
            output.put("balance", customer.getBalance());
        } else {
            output.put("status", "not_found");
            output.put("message", "No customer found with that account number");
        }
    }
}
```

## Benefits of This Architecture

1. **Modularity**: Each tool is self-contained
2. **Easy to Extend**: Just implement the `Tool` interface
3. **No Code Changes**: Add tools without modifying existing handlers
4. **Testable**: Each tool can be tested independently
5. **Reusable**: Tools can be shared across different handlers
6. **Type Safe**: Interface ensures all required methods are implemented
7. **Clear Contracts**: Tool definitions are in one place

## Best Practices

1. **Tool Names**: Use camelCase ending with "Tool" (e.g., `sendEmailTool`)
2. **Descriptions**: Be specific about when Nova should use the tool
3. **Error Handling**: Always catch exceptions and populate error status
4. **Logging**: Use SLF4J logger for debugging
5. **Output Format**: Use consistent keys like `status`, `message`, `result`
6. **Parameters**: Use JSON schema for complex parameters
7. **Dependencies**: Pass dependencies via constructor (dependency injection)

## Testing

```java
@Test
public void testMyCustomTool() {
    MyCustomTool tool = new MyCustomTool();
    Map<String, Object> output = new HashMap<>();

    tool.handle("test-id", "{}", output);

    assertEquals("success", output.get("status"));
}
```

## Migration from OTPNovaS2SEventHandler

The original `OTPNovaS2SEventHandler` is still available but deprecated.
To migrate:

1. Replace `OTPNovaS2SEventHandler` with `ModularNovaS2SEventHandler`
2. Same constructor signature - no code changes needed!
3. All existing functionality preserved
4. Now you can easily add more tools

## Questions?

See existing tools (`SendOTPTool`, `VerifyOTPTool`, `HangupTool`) for reference implementations.
