# Dynamic Prompt Routing

The voice gateway supports dynamic prompt selection based on call context. You can route calls to different prompts based on:
- **Caller phone number** - Who is calling
- **Called number (DID)** - Which number they dialed
- **Environment variable** - Override via `NOVA_PROMPT_FILE`

## Priority Order

The system selects prompts in this priority order:

1. **Caller-specific routing** - Highest priority
2. **DID-specific routing** - Second priority
3. **Environment variable** (`NOVA_PROMPT_FILE`) - Third priority
4. **Default from config** - Lowest priority

## Configuration

### Using prompt-routing.properties (Recommended)

Edit `src/main/resources/prompt-routing.properties`:

```properties
# Route specific callers to specific prompts
caller.+15551234567=prompts/vip-customer.prompt
caller.+15559999999=prompts/customer-support.prompt

# Route based on called number (DID)
did.+18005551000=prompts/sales.prompt
did.+18005552000=prompts/customer-support.prompt
did.+18005553000=prompts/general-assistant.prompt

# Default prompt (used when no rules match)
default=prompts/default.prompt
```

### Using Environment Variable

Set `NOVA_PROMPT_FILE` to override the default:

```bash
export NOVA_PROMPT_FILE=prompts/customer-support.prompt
```

Or in `/etc/nova-gateway.env`:
```
NOVA_PROMPT_FILE=prompts/customer-support.prompt
```

### Programmatic Configuration

You can also configure routing at runtime:

```java
// Add caller-specific routing
PromptSelector.addCallerRouting("+15551234567", "prompts/vip-customer.prompt");

// Add DID-specific routing
PromptSelector.addDIDRouting("+18005551000", "prompts/sales.prompt");

// Clear all routing
PromptSelector.clearRouting();
```

## Use Cases

### Use Case 1: Multiple Business Lines

You have three phone numbers for different departments:

```properties
# Sales line
did.+18005551000=prompts/sales.prompt

# Support line
did.+18005552000=prompts/customer-support.prompt

# General line
did.+18005553000=prompts/general-assistant.prompt
```

Now each number uses a different prompt with different tools and behaviors.

### Use Case 2: VIP Customers

Route known VIP customers to a special prompt:

```properties
# VIP customers get special treatment
caller.+15551111111=prompts/vip-customer.prompt
caller.+15552222222=prompts/vip-customer.prompt

# Everyone else uses default
default=prompts/customer-support.prompt
```

### Use Case 3: A/B Testing

Test different prompts with different groups:

```properties
# Group A - Original prompt
caller.+15551000000=prompts/prompt-a.prompt
caller.+15551000001=prompts/prompt-a.prompt

# Group B - Experimental prompt
caller.+15552000000=prompts/prompt-b.prompt
caller.+15552000001=prompts/prompt-b.prompt
```

### Use Case 4: Time-Based Routing

While not built-in, you can implement time-based routing:

```java
// In a custom initialization class
String prompt = LocalTime.now().getHour() < 17
    ? "prompts/business-hours.prompt"
    : "prompts/after-hours.prompt";

System.setProperty("NOVA_PROMPT_FILE", prompt);
```

## Available Prompts

The gateway includes these default prompts:

- **`prompts/default.prompt`** - All tools enabled (OTP, DateTime, Hangup, GetCallerPhone)
- **`prompts/customer-support.prompt`** - Customer support focused (OTP, Hangup, GetCallerPhone)
- **`prompts/general-assistant.prompt`** - General assistant (DateTime, Hangup, GetCallerPhone)

## Creating Custom Prompts

Create a new `.prompt` file in `src/main/resources/prompts/`:

```
You are a sales assistant helping customers purchase products.
Be friendly, persuasive, and helpful.
Keep responses brief (2-3 sentences).

@tool getCallerPhoneTool
@tool hangupTool
```

Then reference it in routing:

```properties
did.+18005551000=prompts/sales.prompt
```

## Logging

The system logs prompt selection:

```
INFO  PromptSelector - Loaded caller routing: +15551234567 -> prompts/vip-customer.prompt
INFO  PromptSelector - Selected prompt 'prompts/vip-customer.prompt' based on caller phone: +15551234567
INFO  NovaStreamerFactory - Loaded prompt configuration from: prompts/vip-customer.prompt
INFO  NovaStreamerFactory - Enabled tools: [getCallerPhoneTool, hangupTool]
```

## Troubleshooting

**Routing not working?**
- Check phone number format matches exactly (e.g., `+15551234567`)
- Verify `prompt-routing.properties` is in classpath (`src/main/resources/`)
- Check logs for "Loaded prompt routing configuration"
- Ensure prompt file exists in `src/main/resources/prompts/`

**Wrong prompt being used?**
- Remember priority order: caller > DID > env var > default
- Check for `NOVA_PROMPT_FILE` environment variable override
- Review logs to see which rule matched

**Properties file not loading?**
- Rebuild project: `mvn clean package`
- Verify file is in JAR: `jar tf target/*.jar | grep prompt-routing.properties`
- Check for syntax errors in properties file

## Dynamic Updates

To update routing without rebuilding:

1. **Environment variable method**: Change env var and restart service
   ```bash
   # Update environment
   echo "NOVA_PROMPT_FILE=prompts/new-prompt.prompt" | sudo tee -a /etc/nova-gateway.env
   sudo systemctl restart nova-gateway
   ```

2. **Properties file method**: Rebuild and redeploy JAR
   ```bash
   # Edit prompt-routing.properties
   # Then rebuild and deploy
   ./scripts/build-and-deploy.sh
   ```

## Best Practices

1. **Use DIDs for department routing** - Route by called number for different business functions
2. **Use caller routing sparingly** - Only for VIP or known problematic callers
3. **Test prompts thoroughly** - Ensure tools work correctly with each prompt
4. **Document your routing** - Comment complex routing rules
5. **Monitor logs** - Track which prompts are being selected
6. **Keep prompts focused** - Each prompt should have a clear purpose
