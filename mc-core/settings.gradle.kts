rootProject.name = "mc-core"

include(
    "core-common",
    "core-data",
    "core-paper",
    "core-velocity",
    // pluginy trybow (lekkie, depend: [CorePaper]) - wgrywane tylko na shardy danego trybu
    "mode-hub",
    "mode-survival",
    "mode-oceanblock",
)
