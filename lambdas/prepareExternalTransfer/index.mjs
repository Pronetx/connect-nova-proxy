/**
 * Lambda: Prepare External Transfer
 *
 * This Lambda is invoked from an Amazon Connect flow before executing an
 * External Voice Transfer. It extracts the contact context and sets custom
 * SIP headers that will be sent to the Voice Gateway.
 *
 * Owner: Dev B
 */

import { ConnectClient, UpdateContactAttributesCommand } from '@aws-sdk/client-connect';

const connect = new ConnectClient({});

/**
 * Lambda handler
 *
 * Input: Connect Contact Flow event
 * Output: Custom SIP headers to attach to external transfer
 */
export const handler = async (event) => {
  console.log('Received event:', JSON.stringify(event, null, 2));

  try {
    // Extract contact context from event
    const contactId = event.Details.ContactData.ContactId;
    const initialContactId = event.Details.ContactData.InitialContactId;
    const instanceId = event.Details.ContactData.InstanceARN.split('/').pop();
    const contactFlowId = event.Details.ContactData.ContactFlowId;

    // Generate correlation ID for tracking
    const correlationId = `${initialContactId}-${Date.now()}`;

    console.log('Contact context:', {
      contactId,
      initialContactId,
      instanceId,
      correlationId
    });

    // Build SIP headers
    // These will be attached to the SIP INVITE sent to the gateway
    const sipHeaders = {
      'X-Connect-ContactId': initialContactId,
      'X-Connect-InstanceId': instanceId,
      'X-Correlation-Id': correlationId,
      'X-Contact-FlowId': contactFlowId
    };

    // Optional: Store correlation ID in contact attributes for later reference
    try {
      await connect.send(new UpdateContactAttributesCommand({
        InitialContactId: initialContactId,
        InstanceId: instanceId,
        Attributes: {
          'correlation_id': correlationId,
          'nova_transfer_started': new Date().toISOString()
        }
      }));
      console.log('Updated contact attributes with correlation ID');
    } catch (error) {
      // Don't fail the transfer if attribute update fails
      console.error('Failed to update contact attributes:', error);
    }

    // Return SIP headers
    // Connect will attach these to the external transfer INVITE
    return {
      statusCode: 200,
      headers: sipHeaders,
      body: {
        message: 'SIP headers prepared successfully',
        correlationId
      }
    };

  } catch (error) {
    console.error('Error preparing external transfer:', error);

    // Return error but allow transfer to proceed (gateway will handle missing headers)
    return {
      statusCode: 500,
      body: {
        message: 'Failed to prepare SIP headers',
        error: error.message
      }
    };
  }
};

/**
 * Example event structure:
 *
 * {
 *   "Details": {
 *     "ContactData": {
 *       "ContactId": "abc-123-def-456",
 *       "InitialContactId": "abc-123-def-456",
 *       "InstanceARN": "arn:aws:connect:us-east-1:123456789012:instance/xyz-789",
 *       "ContactFlowId": "flow-123",
 *       "Attributes": {},
 *       "CustomerEndpoint": {
 *         "Address": "+12065551234",
 *         "Type": "TELEPHONE_NUMBER"
 *       }
 *     },
 *     "Parameters": {}
 *   }
 * }
 *
 * Example return value:
 *
 * {
 *   "statusCode": 200,
 *   "headers": {
 *     "X-Connect-ContactId": "abc-123-def-456",
 *     "X-Connect-InstanceId": "xyz-789",
 *     "X-Correlation-Id": "abc-123-def-456-1729872737123",
 *     "X-Contact-FlowId": "flow-123"
 *   },
 *   "body": {
 *     "message": "SIP headers prepared successfully",
 *     "correlationId": "abc-123-def-456-1729872737123"
 *   }
 * }
 */
