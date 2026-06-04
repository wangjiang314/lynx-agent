# Security Policy

## Supported Status

Lynx Agent is currently an engineering preview. Security fixes are best-effort,
and there is not yet a formal release support window.

## Reporting a Vulnerability

Please do not open public issues for:

1. leaked credentials
2. private endpoint exposure
3. user data disclosure
4. privilege escalation paths
5. unsafe default configuration

Instead, report the issue privately to the maintainer before public disclosure.

When reporting, include:

1. a short description of the issue
2. affected files or components
3. reproduction steps if available
4. potential impact
5. whether sensitive logs, screenshots, or traces are involved

## Sensitive Data Guidelines

This project touches phone automation, screenshots, OCR, and traces. Please be
careful not to publish:

1. API keys or private endpoints
2. screenshots with personal data
3. benchmark traces containing account details
4. device identifiers or credentials

The public repository should use placeholder defaults only.
