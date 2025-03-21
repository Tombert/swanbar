# Swanbar

A custom Sway title bar written in Clojure and compiled with GraalVM. 

# Why? 

I don't know.  I had a custom title bar written with Bash that did all this stuff, but it got messy.  The protocol for the Sway is pretty easy, and so I thought it might be fun to overengineer the hell out of it. 

And overengineer I did!  I used `core.async` liberally to minimize blocking, and I have plans...plans I tell you! 

# Setup

This is a basic multimethod framework which is fairly customizable. 

For any module you want to create, you need to make implement two methods: `fetchdata` and `render`.  

As of this writing, these have to be blocking (async coming soon), and at minimum must return a `:data` object. 

Your implementation should look something like 

```
(defmethod fetch-data :my-module [_ timeout] 
    {:data {}}
)
```

This object can be arbitrary, it just needs to have all the information you care about for rendering. 

The `timeout` variable is used for memoizing and expiration.  If you would like to have somethig only run on certain intervals (e.g. something that calls a web API that's rate-limited or expensive), you can also add a key `:expires` to the method with the expiration epoch time in milliseconds. 


Your render function should look something like this: 

```
(defmethod render :my-module [_ render-data]
  {:out (str (:item render-data))})
```

The `:out` value *must* be a string. Otherwise there are no limitations.  The `render-data` is the `:data` from the `fetch-data` function. 


You will also need a JSON configuration file.  

The file will be one big ol' array of objects.  

```
[
  {
	  "name": "quote",
	  "timeout": 480000,
	  "color": "#EEEEEE",
	  "background": "#222222"
  },
  {
	  "name": "volume",
	  "timeout": 0 
  }
```

All fields other than `name` are optional. `name` *must* match the keyword used in your multimethods. 

You must then pass in this JSON as a parameter when launching the swaybar. 


```
bar {
    position top 
    status_command  ~/.config/sway/swaybar2 ~/.config/sway/swaybar-config.json
}
```
