s/\$fatal/xs_assert(`__LINE__)/g
s/assign ([a-zA-Z0-9_]+) = .* \? (.*) : [0-9]+'bx;/assign \1 = \2;/g
s/_LOG_MODULE_PATH_/%m/g
/FILE "firrtl_black_box_resource_files\.f"/,$d
/^  .*DummyDPICWrapper/i\`ifndef SYNTHESIS
/^  .*DummyDPICWrapper/{:L0; N; /;/!b L0; s/;/;\n`endif/ };
/^  .*Delayer(_[0-9]*)? difftest/i\`ifndef SYNTHESIS
/^  .*Delayer(_[0-9]*)? difftest/{:L1; N; /;/!b L1; s/;/;\n`endif/ };