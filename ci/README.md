# Development signing only

`development.p12` is a deliberately public test identity (password `android`, alias `androiddebugkey`). It keeps the phone/watch development APKs on the same certificate and makes MVP upgrades possible. It is not a secret and must never sign production or Play Store releases. The CI does not use the developer's personal debug certificate.
