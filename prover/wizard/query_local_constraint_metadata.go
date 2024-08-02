// Code generated by bavard DO NOT EDIT

package wizard
import (
	"strconv"

	"github.com/consensys/gnark/frontend"
)

func (i *QueryLocalConstraint) WithTags(tags ...string) *QueryLocalConstraint {
	i.metadata.tags = append(i.metadata.tags, tags...)
	return i
}

func (i *QueryLocalConstraint) WithName(name string) *QueryLocalConstraint {
	i.metadata.name = name
	return i
}

func (i *QueryLocalConstraint) WithDoc(doc string) *QueryLocalConstraint {
	i.metadata.doc = doc
	return i
}

func (i *QueryLocalConstraint) Tags() []string {
	return i.metadata.tags
}

func (i *QueryLocalConstraint) ListTags() []string {
	return i.metadata.listTags()
}

func (i *QueryLocalConstraint) String() string {
	return i.metadata.scope.getFullScope() + "/" + i.metadata.nameOrDefault(i) + "/" + strconv.Itoa(int(i.metadata.id))
}

func (i *QueryLocalConstraint) Explain() string {
	return i.metadata.explain(i)
}
func (i *QueryLocalConstraint) id() id {
	return i.metadata.id
}
// ComputeResult does not return any result for [QueryLocalConstraint] because Global
// constraints do not return results as they are purely constraints that must
// be satisfied.
func (i QueryLocalConstraint) ComputeResult(run Runtime) QueryResult {
	return &QueryResNone{}
}

// ComputeResult does not return any result for [QueryLocalConstraint] because Global
// constraints do not return results.
func (i QueryLocalConstraint) ComputeResultGnark(_ frontend.API, run GnarkRuntime) QueryResultGnark {
	return &QueryResNoneGnark{}
}