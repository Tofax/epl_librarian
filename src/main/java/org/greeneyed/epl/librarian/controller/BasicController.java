package org.greeneyed.epl.librarian.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Controller
@Data
@RequiredArgsConstructor(onConstructor = @__({ @Autowired }))
public class BasicController {

  @GetMapping(value = "/")
  public String root() {
    return "redirect:/librarian/";
  }

  @GetMapping(value = "/librarian/")
  public String main() {
    return "main";
  }

}
