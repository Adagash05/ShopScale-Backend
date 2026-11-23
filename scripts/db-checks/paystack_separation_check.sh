#!/usr/bin/env bash
# Static check: the AMSATI platform key (app.paystack.secret-key / PAYSTACK_SECRET_KEY) must only be used by subscription billing,
# and merchant storefront payments must only use the merchant's own decrypted key. Run from the repository root.
set -u
fail=0
src=src/main/java/com/amsati/shopscale
users=$(grep -rln 'app.paystack.secret-key' $src | sort)
echo "Files that read the AMSATI platform key:"; echo "$users" | sed 's/^/  /'
for f in $users; do case "$f" in */subscription/*|*/common/ProductionConfigValidator.java) ;; *) echo "FAIL: $f reads the platform key"; fail=1;; esac; done
if grep -rn 'app.paystack\|BillingService\|billing\.' $src/store | grep -v '^.*://' | grep -q .; then echo "FAIL: storefront code references platform billing/key"; grep -rn 'app.paystack\|BillingService' $src/store; fail=1; fi
grep -q 'box.decrypt(s.getPaystackSecretEnc())' $src/store/StoreOrderService.java || { echo "FAIL: storefront does not use the merchant's stored key"; fail=1; }
if grep -rn 'secretKey' $src/store | grep -qv 'PaystackRequest\|secretKey()'; then echo "NOTE: review secretKey mentions in store/"; grep -rn 'secretKey' $src/store | head; fi
[ $fail -eq 0 ] && echo "PASS: platform key stays in subscription billing; storefront uses merchant keys only" || exit 1
