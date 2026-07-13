# Security Policy

## Supported Versions

schema2class is pre-1.0. Security fixes are made on `main` and released in the
next available version. Once stable release branches exist, this policy will be
updated with a supported-version table.

## Reporting a Vulnerability

Do not report security issues in public GitHub issues.

Use GitHub's private vulnerability reporting for this repository when available,
or email the maintainer at `esmcelroy@hey.com` with:

- affected version or commit
- dependency or module involved
- reproduction steps or proof-of-concept details
- expected impact
- any known mitigations

Expected response:

- initial acknowledgement within 7 days
- status update or remediation plan within 14 days when the report is valid
- coordinated disclosure timing agreed with the reporter for confirmed issues

## Dependency Reports

For vulnerable dependencies, include the advisory identifier, affected module,
fixed version, and whether the vulnerable code path is reachable from normal
schema parsing, code generation, CLI, or Gradle plugin use.
