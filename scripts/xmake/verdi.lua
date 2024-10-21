import("core.base.option")
-- verilator
-- image
function verdi ()
  assert(option.get("image"))
  local sim_dir = path.join(os.curdir(), "sim")
  if option.get("verilator") then
    sim_dir = path.join(sim_dir, "emu")
  else
    sim_dir = path.join(sim_dir, "simv")
  end
  sim_dir = path.join(sim_dir, option.get(image))

  local cmds = "verdi -ssf "
  if option.get("verilator") then
    cmds = cmds .. option.get("image") .. ".vcd"
  else
    cmds = cmds .. "tb_top.vf -dbdir simv.daidir" 
  end
  io.writefile("verdi.sh", cmds)
  print(cmds)
  os.execv(os.shell(), { "verdi.sh" })
  os.rm("verdi.sh")
end