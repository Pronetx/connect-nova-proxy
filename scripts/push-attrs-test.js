#!/usr/bin/env node

/**
 * Contact Attributes Test Script
 *
 * Tests UpdateContactAttributes API calls to Amazon Connect.
 * This allows Dev B to test Connect integration without full call setup.
 *
 * Owner: Dev B
 *
 * Usage:
 *   node push-attrs-test.js --contact-id <id> --instance-id <id>
 *   node push-attrs-test.js --help
 */

import { ConnectClient, UpdateContactAttributesCommand } from '@aws-sdk/client-connect';
import { parseArgs } from 'node:util';

// Parse command line arguments
const { values: args } = parseArgs({
  options: {
    'contact-id': { type: 'string' },
    'instance-id': { type: 'string' },
    'region': { type: 'string', default: 'us-east-1' },
    'profile': { type: 'string' },
    'test': { type: 'string', default: 'basic' },
    'help': { type: 'boolean', short: 'h' }
  }
});

if (args.help) {
  console.log(`
Contact Attributes Test Script

Usage:
  node push-attrs-test.js --contact-id <id> --instance-id <id> [options]

Options:
  --contact-id <id>     Initial Contact ID (required)
  --instance-id <id>    Connect Instance ID (required)
  --region <region>     AWS region (default: us-east-1)
  --profile <profile>   AWS profile to use
  --test <name>         Test scenario to run (default: basic)
                        Options: basic, agent-transfer, mid-call, size-limit
  --help, -h           Show this help message

Test Scenarios:
  basic         - Simple attribute update (nova_next=end)
  agent-transfer - Full agent transfer attributes
  mid-call      - Multiple incremental updates
  size-limit    - Test 32KB size limit handling

Examples:
  # Basic test
  node push-attrs-test.js \\
    --contact-id abc-123-def-456 \\
    --instance-id xyz-789

  # Agent transfer test with custom profile
  node push-attrs-test.js \\
    --contact-id abc-123 \\
    --instance-id xyz-789 \\
    --profile prod \\
    --test agent-transfer

  # Test against active call (get contact ID from Connect console)
  node push-attrs-test.js \\
    --contact-id $(aws connect list-contacts --instance-id xyz-789 --query 'ContactSummaryList[0].Id' --output text) \\
    --instance-id xyz-789
`);
  process.exit(0);
}

// Validate required arguments
if (!args['contact-id'] || !args['instance-id']) {
  console.error('ERROR: --contact-id and --instance-id are required');
  console.error('Run with --help for usage information');
  process.exit(1);
}

const contactId = args['contact-id'];
const instanceId = args['instance-id'];
const region = args.region;
const testScenario = args.test;

// Initialize Connect client
const clientConfig = { region };
if (args.profile) {
  const { fromIni } = await import('@aws-sdk/credential-providers');
  clientConfig.credentials = fromIni({ profile: args.profile });
}
const connect = new ConnectClient(clientConfig);

console.log('==========================================');
console.log('Connect Attribute Update Test');
console.log('==========================================');
console.log(`Contact ID:    ${contactId}`);
console.log(`Instance ID:   ${instanceId}`);
console.log(`Region:        ${region}`);
console.log(`Test Scenario: ${testScenario}`);
console.log('==========================================\n');

/**
 * Update contact attributes
 */
async function updateAttributes(attributes, description) {
  console.log(`\n📤 ${description}`);
  console.log('Attributes:', JSON.stringify(attributes, null, 2));

  try {
    const command = new UpdateContactAttributesCommand({
      InitialContactId: contactId,
      InstanceId: instanceId,
      Attributes: attributes
    });

    const startTime = Date.now();
    await connect.send(command);
    const duration = Date.now() - startTime;

    console.log(`✅ Success! (${duration}ms)`);
    return true;
  } catch (error) {
    console.error(`❌ Error: ${error.name}`);
    console.error(`   Message: ${error.message}`);
    if (error.Code) console.error(`   Code: ${error.Code}`);
    return false;
  }
}

/**
 * Calculate total attribute size
 */
function calculateSize(attributes) {
  return Object.entries(attributes).reduce(
    (total, [key, value]) => total + key.length + value.length,
    0
  );
}

/**
 * Test Scenarios
 */
const tests = {
  /**
   * Basic - Simple end call
   */
  async basic() {
    const attributes = {
      nova_next: 'end',
      nova_summary: 'Test call - issue resolved',
      nova_reason: 'issue_resolved',
      nova_timestamp: new Date().toISOString(),
      nova_call_duration: '45'
    };

    console.log(`Size: ${calculateSize(attributes)} bytes`);
    return await updateAttributes(attributes, 'Basic End Call Test');
  },

  /**
   * Agent Transfer - Full transfer scenario
   */
  async 'agent-transfer'() {
    const attributes = {
      nova_next: 'agent',
      nova_target_queue: 'BasicQueue',
      nova_summary: 'Customer needs billing assistance. Account: ACC-12345. Issue: Disputed charge $49.99.',
      nova_reason: 'needs_agent',
      nova_timestamp: new Date().toISOString(),
      nova_call_duration: '127',
      nova_customer_id: 'CUST-67890',
      nova_issue_type: 'billing',
      nova_severity: 'medium',
      nova_customer_sentiment: 'frustrated'
    };

    console.log(`Size: ${calculateSize(attributes)} bytes`);
    return await updateAttributes(attributes, 'Agent Transfer Test');
  },

  /**
   * Mid-Call - Multiple incremental updates
   */
  async 'mid-call'() {
    console.log('This test simulates multiple attribute updates during a call\n');

    // Update 1: Customer identified
    let success = await updateAttributes(
      {
        nova_customer_id: 'CUST-12345',
        nova_account_number: 'ACC-98765'
      },
      'Update 1: Customer Identification'
    );
    if (!success) return false;

    await new Promise(resolve => setTimeout(resolve, 1000));

    // Update 2: Issue categorized
    success = await updateAttributes(
      {
        nova_issue_type: 'technical',
        nova_issue_subtype: 'internet_outage',
        nova_severity: 'high'
      },
      'Update 2: Issue Categorization'
    );
    if (!success) return false;

    await new Promise(resolve => setTimeout(resolve, 1000));

    // Update 3: Sentiment detected
    success = await updateAttributes(
      {
        nova_customer_sentiment: 'frustrated',
        nova_intent: 'resolve_issue'
      },
      'Update 3: Sentiment Analysis'
    );
    if (!success) return false;

    await new Promise(resolve => setTimeout(resolve, 1000));

    // Update 4: Final disposition
    success = await updateAttributes(
      {
        nova_next: 'agent',
        nova_target_queue: 'TechnicalSupport',
        nova_summary: 'Internet outage reported. Confirmed service issue in customer area. Transferring to technical support.',
        nova_reason: 'needs_escalation',
        nova_timestamp: new Date().toISOString(),
        nova_call_duration: '234'
      },
      'Update 4: Final Disposition'
    );

    return success;
  },

  /**
   * Size Limit - Test 32KB limit handling
   */
  async 'size-limit'() {
    console.log('Testing attribute size limits (32KB Connect limit)\n');

    // Test 1: Normal size (~1KB)
    let attributes = {
      nova_next: 'end',
      nova_summary: 'A'.repeat(500),
      nova_reason: 'issue_resolved',
      nova_timestamp: new Date().toISOString()
    };
    console.log(`Test 1 size: ${calculateSize(attributes)} bytes`);
    let success = await updateAttributes(attributes, 'Test 1: Normal Size (~1KB)');
    if (!success) return false;

    await new Promise(resolve => setTimeout(resolve, 1000));

    // Test 2: Large but valid (~10KB)
    attributes = {
      nova_next: 'agent',
      nova_target_queue: 'Support',
      nova_summary: 'B'.repeat(8000),
      nova_reason: 'needs_agent',
      nova_timestamp: new Date().toISOString(),
      nova_extra_data: 'C'.repeat(1000)
    };
    console.log(`Test 2 size: ${calculateSize(attributes)} bytes`);
    success = await updateAttributes(attributes, 'Test 2: Large but Valid (~10KB)');
    if (!success) return false;

    await new Promise(resolve => setTimeout(resolve, 1000));

    // Test 3: Near limit (~30KB)
    attributes = {
      nova_next: 'end',
      nova_summary: 'D'.repeat(28000),
      nova_reason: 'issue_resolved',
      nova_timestamp: new Date().toISOString()
    };
    console.log(`Test 3 size: ${calculateSize(attributes)} bytes`);
    success = await updateAttributes(attributes, 'Test 3: Near Limit (~30KB)');
    if (!success) {
      console.warn('\n⚠️  This may fail due to size limit. Gateway should truncate before sending.');
      return false;
    }

    return true;
  }
};

/**
 * Run test
 */
async function runTest() {
  if (!tests[testScenario]) {
    console.error(`\n❌ Unknown test scenario: ${testScenario}`);
    console.error(`Available scenarios: ${Object.keys(tests).join(', ')}`);
    process.exit(1);
  }

  console.log(`Running test: ${testScenario}\n`);

  try {
    const success = await tests[testScenario]();

    console.log('\n==========================================');
    if (success) {
      console.log('✅ Test completed successfully!');
      console.log('==========================================\n');

      console.log('Next steps:');
      console.log('1. Check Connect console for updated attributes');
      console.log('2. View contact record details');
      console.log('3. Verify attributes in CloudWatch Logs');

      process.exit(0);
    } else {
      console.log('❌ Test failed - see errors above');
      console.log('==========================================\n');

      console.log('Troubleshooting:');
      console.log('1. Verify contact ID is correct and contact is active');
      console.log('2. Check IAM permissions for UpdateContactAttributes');
      console.log('3. Verify instance ID is correct');
      console.log('4. Check AWS region matches your Connect instance');

      process.exit(1);
    }
  } catch (error) {
    console.error('\n❌ Unexpected error:', error);
    process.exit(1);
  }
}

// Run the test
runTest();
