package server

import "os/exec"

func sudo(cmd string, args ...string) *exec.Cmd {
	return exec.Command("sudo", append([]string{cmd}, args...)...)
}
