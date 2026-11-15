# Platform administration

Superseded by the Super Admin console. See:
* `SUPER_ADMIN_SETUP.md` – creating the platform-owner account and using the console;
* `SUPER_ADMIN_SECURITY.md` – the security model;
* `ENVIRONMENT_VARIABLES.md` – `SHOPSCALE_SUPER_ADMIN_*`.

The earlier "allow-listed merchant account" bootstrap (`PLATFORM_ADMIN_BOOTSTRAP_*`) was **removed**. The only remaining legacy switch is `PLATFORM_ADMIN_EMAILS`, which lets listed, verified merchant accounts open the partner screens at `/admin/partners`; it grants nothing else and can be left empty. The same partner screens are available to the Super Admin at `/super-admin/partners`.
