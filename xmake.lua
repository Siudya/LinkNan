local prj_dir = os.curdir()

target("idea")
  set_kind("phony")

  on_run(function (target)
    os.execv(os.shell(), {"mill", "-i", "mill.idea.GenIdea/idea"})
  end)
target_end()

target("init")
  set_kind("phony")

  on_run(function (target)
    local exec = os.exec
    exec("git submodule update --init")
    os.cd(prj_dir .. "/dependencies/nanhu")
    exec("git submodule update --init coupledL2 huancun")
    os.cd(prj_dir .. "/dependencies/nanhu/coupledL2")
    exec("git submodule update --init AXItoTL")
  end)
target_end()

target("comp")
  set_kind("phony")

  on_run(function (target)
    os.execv(os.shell(), {"mill", "-i", "nansha.compile"})
    os.execv(os.shell(), {"mill", "-i", "nansha.test.compile"})
  end)
target_end()