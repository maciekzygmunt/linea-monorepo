// Code generated by bavard DO NOT EDIT

package wizard
import (
	"strconv"

	"github.com/consensys/gnark/frontend"
	"github.com/consensys/zkevm-monorepo/prover/utils"
	"fmt"
)

func (u *QueryUnivariateEval) WithTags(tags ...string) *QueryUnivariateEval {
	u.metadata.tags = append(u.metadata.tags, tags...)
	return u
}

func (u *QueryUnivariateEval) WithName(name string) *QueryUnivariateEval {
	u.metadata.name = name
	return u
}

func (u *QueryUnivariateEval) WithDoc(doc string) *QueryUnivariateEval {
	u.metadata.doc = doc
	return u
}

func (u *QueryUnivariateEval) Tags() []string {
	return u.metadata.tags
}

func (u *QueryUnivariateEval) ListTags() []string {
	return u.metadata.listTags()
}

func (u *QueryUnivariateEval) String() string {
	return u.metadata.scope.getFullScope() + "/" + u.metadata.nameOrDefault(u) + "/" + strconv.Itoa(int(u.metadata.id))
}

func (u *QueryUnivariateEval) Explain() string {
	return u.metadata.explain(u)
}
func (u *QueryUnivariateEval) id() id {
	return u.metadata.id
}
func (u QueryUnivariateEval) Check(run Runtime) error {
	var (
		v          = u.ComputeResult(run).(*QueryResFE).R
		qr2, okQr2 = run.tryGetQueryRes(&u)
		v2         = qr2.(*QueryResFE).R
	)

	if !okQr2 {
		return fmt.Errorf("error verifying position opening: the result if missing from the proof")
	}

	if v != v2 {
		return fmt.Errorf("error verifying position opening: the result %v does not match the correct value: %v", v2.String(), v.String())
	}

	return nil
}

func (u QueryUnivariateEval) CheckGnark(api frontend.API, run GnarkRuntime) {

	var (
		v          = u.ComputeResultGnark(api, run).(*QueryResFEGnark).R
		qr2, okQr2 = run.tryGetQueryRes(&u)
		v2         = qr2.(*QueryResFEGnark).R
	)

	if !okQr2 {
		utils.Panic("error verifying position opening: missing result from the proof")
	}

	api.AssertIsEqual(v, v2)
}