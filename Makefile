table_images:
	rm -rf tables
	python3 ecu_tables.py "Original Binaries" "Map Definitions/"

.PHONY: kp kp_check
kp:
	python3 ecu_kp.py

kp_check:
	python3 ecu_kp.py --check
