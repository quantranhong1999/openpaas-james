#!/bin/bash

james-cli AddDomain tmail.com
james-cli AddUser alice@tmail.com aliceSecret
james-cli AddUser bob@tmail.com bobSecret
james-cli AddUser empty@tmail.com emptrySecret

declare -a arr=("INBOX" "Important" "tmail" "tmail.mobile" "tmail.backend" "tmail.backend.extensions" "tmail.backend.extensions.pgp" "tmail.backend.extensions.filters" "tmail.backend.extensions.ticketAuth" "tmail.backend.memory" "tmail.backend.distributed" "tmail.marketting" "admin" "customers" "james" "james.dev" "james.user" "james.pmc" "james.dev.gsoc" "Outbox" "Sent" "Drafts" "Trash" "Spam" "loooooooooooooooooooooooooooooooooooooooooooooooooooooooooooong" "a.b.c.d.e.f.g.h.i.j")

for i in "${arr[@]}"
do
   echo "Creating mailbox $i"
   james-cli CreateMailbox \#private alice@tmail.com $i &
   james-cli CreateMailbox \#private bob@tmail.com $i &
   wait
done

for i in "${arr[@]}"
do
   for j in {1..41}
   do
       echo "Importing $j.eml in $i"
       james-cli ImportEml \#private alice@tmail.com $i /root/provisioning/eml/$j.eml &
       james-cli ImportEml \#private bob@tmail.com $i /root/provisioning/eml/$j.eml &
       wait
   done
done

james-cli CreateMailbox \#private alice@tmail.com empty
james-cli CreateMailbox \#private bob@tmail.com empty

james-cli CreateMailbox \#private alice@tmail.com five
james-cli ImportEml \#private alice@tmail.com five 0.eml
james-cli ImportEml \#private alice@tmail.com five 1.eml
james-cli ImportEml \#private alice@tmail.com five 2.eml
james-cli ImportEml \#private alice@tmail.com five 3.eml
james-cli ImportEml \#private alice@tmail.com five 4.eml
james-cli CreateMailbox \#private bob@tmail.com five
james-cli ImportEml \#private bob@tmail.com five 0.eml
james-cli ImportEml \#private bob@tmail.com five 1.eml
james-cli ImportEml \#private bob@tmail.com five 2.eml
james-cli ImportEml \#private bob@tmail.com five 3.eml
james-cli ImportEml \#private bob@tmail.com five 4.eml