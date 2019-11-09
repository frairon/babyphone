package server

import "testing"
import "github.com/stretchr/testify/assert"

func TestHashPassword(t *testing.T) {
	assert.Equal(t, hashPassword(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
	assert.Equal(t, hashPassword("asdf"), "f0e4c2f76c58916ec258f246851bea091d14d4247a2fc3e18694461b1816e13b")
}
