local prj_dir = os.getenv("PWD") or "."

target("idea")
    set_kind("phony")

    -- xmake run idea
    on_run(function (target)
        os.exec("mill -i mill.idea.GenIdea/idea")
    end)

target("init")
    set_kind("phony")

    -- xmake run init
    on_run(function (target)
        local exec = os.exec
        exec("git submodule update --init")
        os.cd(prj_dir .. "/dependencies/nanhu")
        exec("git submodule update --init coupledL2 huancun")
        os.cd(prj_dir .. "/dependencies/nanhu/coupledL2")
        exec("git submodule update --init AXItoTL")
    end)

target("comp")
    set_kind("phony")

    -- xmake run comp
    on_run(function (target)
        os.exec("mill -i nansha.compile")
        os.exec("mill -i nansha.test.compile")
    end)