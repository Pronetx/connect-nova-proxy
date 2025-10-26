/**
 * AWS Lambda Function for SmartyStreets Address Validation
 *
 * Uses SmartyStreets US Street API with enhanced matching mode to validate
 * and standardize US addresses. Returns conversational responses for Nova.
 */

import https from 'https';

const SMARTY_AUTH_ID = process.env.SMARTY_AUTH_ID;
const SMARTY_AUTH_TOKEN = process.env.SMARTY_AUTH_TOKEN;
const SMARTY_ENDPOINT = 'us-street.api.smartystreets.com';

/**
 * Main Lambda handler
 */
export const handler = async (event) => {
    console.log('Address validation request:', JSON.stringify(event, null, 2));

    try {
        // Parse request body
        const body = typeof event.body === 'string' ? JSON.parse(event.body) : event.body;

        const { street, suite, city, state, zipcode, candidates = 5 } = body;

        // Validate required fields
        if (!street || !city || !state) {
            return createResponse(400, {
                status: 'error',
                message: 'Missing required fields: street, city, and state are required',
                conversationalResponse: "I need the street address, city, and state to validate the address. Could you please provide those?"
            });
        }

        // Build SmartyStreets API request
        const address = {
            street: suite ? `${street} ${suite}` : street,
            city: city,
            state: state,
            zipcode: zipcode || '',
            candidates: candidates,
            match: 'enhanced' // Use enhanced matching mode
        };

        console.log('Validating address:', address);

        // Call SmartyStreets API
        const smartyResponse = await callSmartyStreets(address);

        console.log('SmartyStreets response:', JSON.stringify(smartyResponse, null, 2));

        // Process response
        const validationResult = processValidationResponse(smartyResponse, address);

        console.log('Validation result:', JSON.stringify(validationResult, null, 2));

        return createResponse(200, validationResult);

    } catch (error) {
        console.error('Error validating address:', error);
        return createResponse(500, {
            status: 'error',
            message: error.message,
            conversationalResponse: "I encountered an error while validating the address. Could you please repeat the address?"
        });
    }
};

/**
 * Call SmartyStreets US Street API
 */
function callSmartyStreets(address) {
    return new Promise((resolve, reject) => {
        const params = new URLSearchParams({
            'auth-id': SMARTY_AUTH_ID,
            'auth-token': SMARTY_AUTH_TOKEN,
            street: address.street,
            city: address.city,
            state: address.state,
            zipcode: address.zipcode,
            candidates: address.candidates,
            match: address.match
        });

        const options = {
            hostname: SMARTY_ENDPOINT,
            path: `/street-address?${params.toString()}`,
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        };

        const req = https.request(options, (res) => {
            let data = '';

            res.on('data', (chunk) => {
                data += chunk;
            });

            res.on('end', () => {
                try {
                    const jsonResponse = JSON.parse(data);
                    resolve(jsonResponse);
                } catch (e) {
                    reject(new Error(`Failed to parse SmartyStreets response: ${e.message}`));
                }
            });
        });

        req.on('error', (error) => {
            reject(new Error(`SmartyStreets API request failed: ${error.message}`));
        });

        req.end();
    });
}

/**
 * Process SmartyStreets validation response and create conversational output
 */
function processValidationResponse(smartyResponse, originalAddress) {
    // No matches found
    if (!smartyResponse || smartyResponse.length === 0) {
        return {
            status: 'invalid',
            message: 'Address not found',
            conversationalResponse: `I couldn't find that address in the postal database. The address "${originalAddress.street}, ${originalAddress.city}, ${originalAddress.state}" doesn't appear to be valid. Could you please verify the address and try again?`,
            originalAddress: originalAddress
        };
    }

    const topCandidate = smartyResponse[0];
    const components = topCandidate.components;
    const metadata = topCandidate.metadata;
    const analysis = topCandidate.analysis;

    // Build standardized address
    const standardizedAddress = {
        street: topCandidate.delivery_line_1,
        suite: topCandidate.delivery_line_2 || '',
        city: components.city_name,
        state: components.state_abbreviation,
        zipcode: `${components.zipcode}-${components.plus4_code}`
    };

    // Determine validation result based on DPV (Delivery Point Validation)
    const dpvMatchCode = analysis.dpv_match_code;
    const dpvFootnotes = analysis.dpv_footnotes || '';

    // Check for exact match (Y = Yes, confirmed; S = Secondary confirmed; D = Missing secondary)
    if (dpvMatchCode === 'Y') {
        // Perfect match - address is valid and deliverable
        return {
            status: 'valid',
            message: 'Address validated successfully',
            conversationalResponse: formatConfirmationResponse(standardizedAddress, originalAddress, false),
            standardizedAddress: standardizedAddress,
            originalAddress: originalAddress,
            metadata: {
                recordType: metadata.record_type,
                countyName: metadata.county_name,
                precision: metadata.precision,
                timeZone: metadata.time_zone,
                utcOffset: metadata.utc_offset,
                dstObserved: metadata.dst
            }
        };
    } else if (dpvMatchCode === 'S' || dpvMatchCode === 'D') {
        // Missing or invalid secondary (apartment/suite)
        return {
            status: 'missing_secondary',
            message: 'Address found but missing apartment/suite number',
            conversationalResponse: `I found the street address at ${standardizedAddress.street} in ${standardizedAddress.city}, ${standardizedAddress.state}, but it appears this is a multi-unit building. Could you please provide the apartment or suite number?`,
            standardizedAddress: standardizedAddress,
            originalAddress: originalAddress,
            suggestedAction: 'request_suite'
        };
    } else if (dpvMatchCode === 'N') {
        // Address not found, but we may have suggestions
        if (smartyResponse.length > 1) {
            // Multiple candidates - provide suggestions
            const suggestions = smartyResponse.slice(0, 3).map(candidate => ({
                street: candidate.delivery_line_1,
                suite: candidate.delivery_line_2 || '',
                city: candidate.components.city_name,
                state: candidate.components.state_abbreviation,
                zipcode: `${candidate.components.zipcode}-${candidate.components.plus4_code}`
            }));

            return {
                status: 'ambiguous',
                message: 'Multiple matching addresses found',
                conversationalResponse: formatSuggestionsResponse(suggestions, originalAddress),
                suggestions: suggestions,
                originalAddress: originalAddress
            };
        } else {
            // Single candidate but not validated - suggest correction
            return {
                status: 'suggestion',
                message: 'Address not validated, but similar address found',
                conversationalResponse: formatCorrectionResponse(standardizedAddress, originalAddress),
                suggestedAddress: standardizedAddress,
                originalAddress: originalAddress
            };
        }
    }

    // Unknown validation status
    return {
        status: 'unknown',
        message: `Validation returned unexpected status: ${dpvMatchCode}`,
        conversationalResponse: `I had trouble validating that address. Could you please verify the address is correct and try again?`,
        originalAddress: originalAddress
    };
}

/**
 * Format confirmation response for valid address
 */
function formatConfirmationResponse(standardized, original, hasCorrections) {
    const streetSpelled = spellStreetName(standardized.street);
    const parts = [
        standardized.street.split(' ').map(w => w.split('').join('-')).join(' '),
        standardized.suite ? standardized.suite.split('').join('-') : null,
        standardized.city,
        standardized.state,
        standardized.zipcode.split('').join('-')
    ].filter(Boolean);

    if (hasCorrections) {
        return `I found the address with some corrections: ${parts.join(', ')}. That's ${streetSpelled}. Is this correct?`;
    } else {
        return `I've validated the address: ${parts.join(', ')}. That's ${streetSpelled}. This address is confirmed.`;
    }
}

/**
 * Format response for correction suggestion
 */
function formatCorrectionResponse(suggested, original) {
    const streetSpelled = spellStreetName(suggested.street);
    const parts = [
        suggested.street.split(' ').map(w => w.split('').join('-')).join(' '),
        suggested.suite ? suggested.suite.split('').join('-') : null,
        suggested.city,
        suggested.state,
        suggested.zipcode.split('').join('-')
    ].filter(Boolean);

    return `I couldn't validate the exact address, but I found something similar: ${parts.join(', ')}. That's ${streetSpelled}. Is this the correct address?`;
}

/**
 * Format response with multiple suggestions
 */
function formatSuggestionsResponse(suggestions, original) {
    if (suggestions.length === 0) {
        return `I couldn't find an exact match for that address. Could you please verify the street address, city, and state?`;
    }

    const firstSuggestion = suggestions[0];
    const parts = [
        firstSuggestion.street.split(' ').map(w => w.split('').join('-')).join(' '),
        firstSuggestion.suite ? firstSuggestion.suite.split('').join('-') : null,
        firstSuggestion.city,
        firstSuggestion.state
    ].filter(Boolean);

    return `I found multiple possible matches. Did you mean ${parts.join(', ')}? Or would you like to hear the other options?`;
}

/**
 * Spell out street name letter by letter for confirmation
 */
function spellStreetName(streetAddress) {
    // Extract street name (everything before the street type like "Street", "Avenue", etc.)
    const parts = streetAddress.split(' ');

    // Find street type keywords
    const streetTypes = ['Street', 'St', 'Avenue', 'Ave', 'Boulevard', 'Blvd', 'Road', 'Rd',
                         'Drive', 'Dr', 'Lane', 'Ln', 'Court', 'Ct', 'Circle', 'Cir',
                         'Way', 'Place', 'Pl', 'Parkway', 'Pkwy'];

    // Find where the street name ends
    let streetNameParts = [];
    for (let i = 0; i < parts.length; i++) {
        if (streetTypes.some(type => parts[i].toLowerCase().startsWith(type.toLowerCase()))) {
            break;
        }
        // Skip numeric parts (house number)
        if (i > 0 && !/^\d+$/.test(parts[i])) {
            streetNameParts.push(parts[i]);
        }
    }

    if (streetNameParts.length === 0) {
        return '';
    }

    const streetName = streetNameParts.join(' ');
    const spelled = streetName.split('').filter(c => c !== ' ').map(c => c.toUpperCase()).join('-');

    return `${spelled} ${parts[parts.length - 1]}`;
}

/**
 * Create HTTP response
 */
function createResponse(statusCode, body) {
    return {
        statusCode,
        headers: {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Headers': 'Content-Type',
            'Access-Control-Allow-Methods': 'POST, OPTIONS'
        },
        body: JSON.stringify(body)
    };
}
