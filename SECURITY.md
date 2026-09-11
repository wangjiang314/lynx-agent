# Security Policy

Lynx Agent can observe and operate an Android device through screenshots,
Accessibility, OCR, and model-selected actions. Treat it as an experimental
automation runtime.

## Supported Versions

The project is currently in engineering preview. Security fixes should target
the default branch unless a release branch is created later.

## Reporting A Vulnerability

Please open a private report through GitHub Security Advisories if available, or
open an issue with minimal public detail and ask for a private follow-up channel.

Do not include API keys, personal screenshots, verification codes, account data,
or other sensitive content in public issues.

## Sensitive Actions

The runtime is designed to require confirmation for sensitive operations such
as payment, deletion, submission, posting, credential-like input, and other
high-risk actions. Security reports around bypassing these confirmations are
especially important.
