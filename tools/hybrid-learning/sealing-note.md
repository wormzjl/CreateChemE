# Report-consistent final sealing

Final `archive.py results` sealing invokes `verify_results.verify_outputs()` before creating an archive. It recomputes selection, reconstructs the complete summary and case map from all required journals, rerenders the report, and requires exact equality with the stored outputs. Mismatches are preserved and stop sealing. ZIP CRC and entry hashes are additional checks after scientific consistency has passed.

This closes the interim review's final-sealing gap without modifying any frozen native benchmark, model, training or selection policy. The previously sealed training snapshot is preserved. The final archive contains the current reporting and verification sources as well as their output bindings.
